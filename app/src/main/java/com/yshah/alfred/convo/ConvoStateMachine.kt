package com.yshah.alfred.convo

import com.yshah.alfred.capture.CaptureMode
import com.yshah.alfred.capture.CaptureState
import com.yshah.alfred.capture.SpeechCaptureController
import com.yshah.alfred.capture.TtsController
import com.yshah.alfred.capture.TtsState
import com.yshah.alfred.data.InteractionDao
import com.yshah.alfred.data.InteractionEntity
import com.yshah.alfred.network.storedMetadata
import com.yshah.alfred.network.WebhookClient
import com.yshah.alfred.network.WebhookResult
import com.yshah.alfred.network.WebhookOutcome
import com.yshah.alfred.network.WebhookRequestMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConvoState {
    data object Idle : ConvoState()
    data object Listening : ConvoState()
    data class PartialTranscript(val text: String) : ConvoState()
    data object Sending : ConvoState()
    data class Speaking(val responseText: String) : ConvoState()
    data class Error(val message: String) : ConvoState()
    data object Ended : ConvoState()
}

private const val MAX_CONSECUTIVE_ERRORS = 2

/**
 * Listening -> Sending -> Speaking -> Listening loop. Deliberately does NOT go through
 * WebhookForegroundService's 300s notify-later path — the user is actively waiting for a spoken
 * reply, so a "notification arrives later" UX would be wrong here. Uses
 * WebhookClient.sendConvoTurn's bounded (120s) in-session timeout instead, and turns
 * errors/timeouts into a spoken in-loop message rather than a deferred notification — see the
 * plan's convo-mode timeout-tension note.
 */
@Singleton
class ConvoStateMachine internal constructor(
    private val speechCaptureController: SpeechCaptureController,
    private val ttsController: TtsController,
    private val webhookClient: WebhookClient,
    private val interactionDao: InteractionDao,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        speechCaptureController: SpeechCaptureController,
        ttsController: TtsController,
        webhookClient: WebhookClient,
        interactionDao: InteractionDao,
    ) : this(speechCaptureController, ttsController, webhookClient, interactionDao,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    private val _state = MutableStateFlow<ConvoState>(ConvoState.Idle)
    val state: StateFlow<ConvoState> = _state

    private var sessionId: String = UUID.randomUUID().toString()
    private var conversationId: String = sessionId
    private var inReplyTo: String? = null
    private var contextToken: String? = null
    private var consecutiveErrors = 0
    private var conversationJob: Job? = null

    fun startConversation() {
        cancelTurn()
        sessionId = UUID.randomUUID().toString()
        conversationId = sessionId
        inReplyTo = null
        contextToken = null
        consecutiveErrors = 0
        listen()
    }

    /** Interrupts TTS mid-speech and immediately starts listening for the next turn. */
    fun bargeIn() {
        if (conversationJob?.isActive != true) return
        cancelTurn()
        listen()
    }

    fun endConversation() {
        cancelTurn()
        _state.value = ConvoState.Ended
    }

    private fun cancelTurn() {
        conversationJob?.cancel()
        conversationJob = null
        speechCaptureController.cancel()
        ttsController.stop()
    }

    private fun listen() {
        val currentSessionId = sessionId
        // Assign before execution: Main.immediate can run a whole turn before launch returns.
        conversationJob = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                currentCoroutineContext().ensureActive()
                _state.value = ConvoState.Listening
                speechCaptureController.start(CaptureMode.AUTO_STOP)
                val capture = speechCaptureController.state.first {
                    if (it is CaptureState.PartialTranscript) {
                        _state.value = ConvoState.PartialTranscript(it.text)
                    }
                    it is CaptureState.Finished || it is CaptureState.Error
                }
                var legacyReply = false
                val reply = if (capture is CaptureState.Finished) {
                    if (capture.requiresReview) {
                        _state.value = ConvoState.Error("Recognition was incomplete. Please retry: ${capture.finalText}")
                        return@launch
                    }
                    val text = capture.finalText
                    if (text.isBlank()) continue
                    _state.value = ConvoState.Sending
                    val metadata = WebhookRequestMetadata(conversationId = conversationId,
                        inReplyTo = inReplyTo, contextToken = contextToken)
                    val result = webhookClient.sendConvoTurn(text, currentSessionId, metadata)
                    currentCoroutineContext().ensureActive()
                    try {
                        recordTurn(text, result, metadata)
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        _state.value = ConvoState.Error("Reply received, but history could not be saved. Check device storage. Retry starts a new conversation.")
                        return@launch
                    }
                    currentCoroutineContext().ensureActive()
                    if (result is WebhookResult.Success) {
                        val response = result.response
                        conversationId = response.conversationId ?: conversationId
                        inReplyTo = metadata.requestId
                        contextToken = response.clarification?.token
                            ?.takeIf { response.outcome == WebhookOutcome.NEEDS_CONFIRMATION }
                        val clarificationQuestion = response.clarification?.question
                            ?.takeIf { response.outcome == WebhookOutcome.NEEDS_CONFIRMATION }
                        legacyReply = response.outcome == WebhookOutcome.UNKNOWN && response.isLegacyResponse
                        val acceptedReply = response.outcome == WebhookOutcome.ACCEPTED &&
                            !response.responseText.isNullOrBlank() && response.clarification == null
                        if (clarificationQuestion == null &&
                            ((!legacyReply && !acceptedReply && response.outcome != WebhookOutcome.COMPLETED) || response.responseText.isNullOrBlank())) {
                            _state.value = ConvoState.Error(when (response.outcome) {
                                WebhookOutcome.ACCEPTED -> "Request accepted, but no completed reply was returned."
                                WebhookOutcome.FAILED -> "The request failed. Check history for details."
                                WebhookOutcome.NEEDS_CONFIRMATION -> "The request needs confirmation. Check history for details."
                                else -> "The server did not return a confirmed reply. Check history for details."
                            })
                            return@launch
                        }
                        consecutiveErrors = 0
                        if (acceptedReply) "Completion is not confirmed. ${response.responseText}"
                        else clarificationQuestion ?: response.responseText
                    } else null
                } else null
                if (reply == null) {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        endConversation()
                        return@launch
                    }
                }
                if (!speak(reply ?: if (capture is CaptureState.Error) {
                    "Didn't catch that."
                } else {
                    "Sorry, that's taking too long."
                }, legacyReply)) {
                    _state.value = ConvoState.Error("Speech playback failed. The full reply is available in history. Retry to continue.")
                    return@launch
                }
            }
        }
        conversationJob?.start()
    }

    /**
     * History uses the exact outbound request ID; session and backend conversation IDs
     * group turns without overwriting earlier requests.
     */
    private suspend fun recordTurn(requestText: String, result: WebhookResult, metadata: WebhookRequestMetadata) {
        val (status, responseText) = when (result) {
            is WebhookResult.Success -> (if (result.response.outcome == WebhookOutcome.UNKNOWN && result.response.isLegacyResponse)
                "delivered" else result.response.outcome.name.lowercase()) to (result.response.responseText ?: result.response.message)
            is WebhookResult.HttpError -> "http_error" to result.body
            is WebhookResult.Timeout -> "timeout" to null
            is WebhookResult.NetworkError -> "network_error" to result.throwable.message
        }
        interactionDao.upsert(
            InteractionEntity(
                sessionId = metadata.requestId,
                type = "convo",
                requestText = requestText,
                timestamp = metadata.capturedAt,
                status = status,
                responseText = responseText,
                responseMetadata = (result as? WebhookResult.Success)?.response?.storedMetadata(),
                conversationId = (result as? WebhookResult.Success)?.response?.conversationId ?: metadata.conversationId,
                inReplyTo = metadata.inReplyTo,
                contextToken = metadata.contextToken,
                httpCode = when (result) {
                    is WebhookResult.Success -> result.httpCode
                    is WebhookResult.HttpError -> result.code
                    else -> null
                },
                timeZone = metadata.timeZone,
            ),
        )
    }

    private suspend fun speak(text: String, legacyReply: Boolean = false): Boolean {
        currentCoroutineContext().ensureActive()
        _state.value = ConvoState.Speaking(if (legacyReply) "$text\n\nLegacy reply — action outcome not verified." else text)
        val utteranceId = UUID.randomUUID().toString()
        ttsController.speak(text, utteranceId)
        val terminal = withTimeoutOrNull( maxOf(30_000L, text.length * 150L)) { ttsController.state.first {
            when (it) {
                is TtsState.Done -> it.utteranceId == utteranceId
                is TtsState.Error -> it.utteranceId == utteranceId
                else -> false
            }
        } }
        if (terminal !is TtsState.Done) ttsController.stop()
        return terminal is TtsState.Done
    }
}
