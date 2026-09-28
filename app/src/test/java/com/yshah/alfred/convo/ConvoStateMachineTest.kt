package com.yshah.alfred.convo

import com.yshah.alfred.capture.*
import com.yshah.alfred.data.InteractionDao
import com.yshah.alfred.data.InteractionEntity
import com.yshah.alfred.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ConvoStateMachineTest {
    private class Capture : SpeechCaptureController {
        override val state = MutableStateFlow<CaptureState>(CaptureState.Finished("stale"))
        var starts = 0
        override fun start(mode: CaptureMode) {
            starts++
            state.value = CaptureState.Listening
        }
        override fun stop() = Unit
        override fun cancel() = Unit // Preserve terminal replay to exercise start ordering.
    }

    private class Tts : TtsController {
        override val state = MutableStateFlow<TtsState>(TtsState.Done("stale"))
        val spoken = mutableListOf<String>()
        var id = ""
        var finishImmediately = false
        override fun speak(text: String, utteranceId: String) {
            spoken += text
            id = utteranceId
            if (finishImmediately) state.value = TtsState.Done(id)
        }
        override fun stop() = Unit
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val capture = Capture()
        val tts = Tts()
        val rows = mutableListOf<InteractionEntity>()
        val requests = mutableListOf<Pair<String, String>>()
        val metadata = mutableListOf<WebhookRequestMetadata>()
        var failStorage = false
        var send: suspend () -> WebhookResult = { WebhookResult.Success(WebhookResponseBody(responseText = "reply", outcome = WebhookOutcome.COMPLETED)) }
        val machine = ConvoStateMachine(capture, tts, object : WebhookClient {
            override suspend fun sendConvoTurn(text: String, sessionId: String, metadata: WebhookRequestMetadata): WebhookResult {
                requests += text to sessionId
                this@Fixture.metadata += metadata
                return send()
            }
            override suspend fun sendTaskOrNote(text: String, type: String, sessionId: String): WebhookResult = error("unused")
            override suspend fun testConnection(): ConnectionTestResult = error("unused")
        }, object : InteractionDao {
            override suspend fun upsert(entity: InteractionEntity) {
                if (failStorage) error("disk full")
                rows += entity
            }
            override fun observeAll() = MutableStateFlow<List<InteractionEntity>>(emptyList())
        }, scope)
    }

    @Test fun clarificationRotatesContextAndCorrelatesHistoryBeforeListening() {
        val f = Fixture()
        try {
            f.send = { WebhookResult.Success(parseResponseBody("""{"status":"needs_confirmation","responseText":"Not the question","conversationId":"backend-thread","clarification":{"token":"opaque+/=","question":"Which time?"}}""")) }
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("Book it")
            assertEquals(listOf("Which time?"), f.tts.spoken)
            assertEquals(1, f.capture.starts)
            val first = f.metadata.single()
            assertEquals(first.requestId, f.rows.single().sessionId)
            assertEquals("backend-thread", f.rows.single().conversationId)
            assertEquals("needs_confirmation", f.rows.single().status)
            assertEquals("opaque+/=", responseMetadata(f.rows.single().responseMetadata).clarification?.token)
            f.tts.state.value = TtsState.Done(f.tts.id)
            assertEquals(ConvoState.Listening, f.machine.state.value)
            f.send = { WebhookResult.Success(parseResponseBody("""{"status":"needs_confirmation","conversationId":"backend-thread-2","clarification":{"token":"rotated","question":"Which calendar?"}}""")) }
            f.capture.state.value = CaptureState.Finished("Noon")
            val second = f.metadata.last()
            assertNotEquals(first.requestId, second.requestId)
            assertEquals(first.requestId, second.inReplyTo)
            assertEquals("backend-thread", second.conversationId)
            assertEquals("opaque+/=", second.contextToken)
            assertEquals(second.requestId, f.rows.last().sessionId)
            assertEquals(second.inReplyTo, f.rows.last().inReplyTo)
            assertEquals(second.contextToken, f.rows.last().contextToken)
            assertEquals(second.capturedAt, f.rows.last().timestamp)
            // Interrupting the question must retain its newly returned context.
            f.machine.bargeIn()
            f.send = { WebhookResult.Success(parseResponseBody("""{"status":"completed","responseText":"Booked"}""")) }
            f.capture.state.value = CaptureState.Finished("Work")
            val third = f.metadata.last()
            assertEquals(second.requestId, third.inReplyTo)
            assertEquals("backend-thread-2", third.conversationId)
            assertEquals("rotated", third.contextToken)
            f.tts.state.value = TtsState.Done(f.tts.id)
            f.capture.state.value = CaptureState.Finished("Another question")
            assertNull(f.metadata.last().contextToken)
            assertEquals(third.requestId, f.metadata.last().inReplyTo)
            assertEquals(f.metadata.size, f.metadata.map { it.requestId }.distinct().size)
            assertEquals(1, f.requests.map { it.second }.distinct().size)
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("Fresh conversation")
            assertNull(f.metadata.last().contextToken)
            assertNull(f.metadata.last().inReplyTo)
            assertEquals(f.requests.last().second, f.metadata.last().conversationId)
        } finally { f.scope.cancel() }
    }

    @Test fun clarificationMetadataCannotOverrideAcceptedOrFailedStatus() {
        for (status in listOf("accepted", "failed")) {
            val f = Fixture()
            try {
                f.send = { WebhookResult.Success(parseResponseBody("""{"status":"$status","responseText":"Done","clarification":{"token":"opaque","question":"Confirm?"}}""")) }
                f.machine.startConversation()
                f.capture.state.value = CaptureState.Finished("request")
                assertEquals(status, f.rows.single().status)
                assertEquals(f.metadata.single().requestId, f.rows.single().sessionId)
                assertTrue(f.tts.spoken.isEmpty())
                assertTrue(f.machine.state.value is ConvoState.Error)
                assertEquals(1, f.capture.starts)
            } finally { f.scope.cancel() }
        }
    }

    @Test fun staleReplayAndWrongUtteranceCannotAdvanceTurn() {
        val f = Fixture()
        try {
            f.machine.startConversation()
            assertTrue(f.requests.isEmpty())
            f.capture.state.value = CaptureState.Finished("first")
            assertEquals(listOf("reply"), f.tts.spoken)
            assertEquals(1, f.capture.starts)
            f.tts.state.value = TtsState.Done("old")
            assertEquals(1, f.capture.starts)
            f.tts.state.value = TtsState.Done(f.tts.id)
            assertEquals(2, f.capture.starts)
            assertEquals(1, f.requests.size)
            f.capture.state.value = CaptureState.Finished("second")
            assertEquals(2, f.requests.size)
            assertEquals(f.requests[0].second, f.requests[1].second)
            assertEquals(2, f.rows.size)
            assertEquals(f.requests[0].second, f.rows[0].conversationId)
            assertEquals(f.rows[0].conversationId, f.rows[1].conversationId)
            assertNotEquals(f.rows[0].sessionId, f.rows[1].sessionId)
        } finally { f.scope.cancel() }
    }

    @Test fun dismissalCancelsPendingSendEvenIfClientReturnsAfterCancellation() {
        val f = Fixture()
        var cancelled = false
        f.send = {
            try { awaitCancellation() } catch (_: CancellationException) {
                cancelled = true
                WebhookResult.Success(WebhookResponseBody(responseText = "late"))
            }
        }
        try {
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("request")
            f.machine.endConversation()
            assertTrue(cancelled)
            assertEquals(ConvoState.Ended, f.machine.state.value)
            assertTrue(f.tts.spoken.isEmpty())
            assertTrue(f.rows.isEmpty())
        } finally { f.scope.cancel() }
    }

    @Test fun immediateCompletionBargeInAndRestartKeepOnlyCurrentLoop() {
        val f = Fixture()
        try {
            f.tts.finishImmediately = true
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("first")
            assertEquals(2, f.capture.starts)
            f.tts.finishImmediately = false
            f.capture.state.value = CaptureState.Finished("second")
            val interruptedId = f.tts.id
            f.machine.bargeIn()
            assertEquals(3, f.capture.starts)
            f.tts.state.value = TtsState.Done(interruptedId)
            assertEquals(3, f.capture.starts)
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("new session")
            assertNotEquals(f.requests.first().second, f.requests.last().second)
            assertEquals(3, f.requests.size)
            f.machine.endConversation()
            f.tts.state.value = TtsState.Done(f.tts.id)
            f.machine.bargeIn()
            assertEquals(4, f.capture.starts)
            assertEquals(ConvoState.Ended, f.machine.state.value)
        } finally { f.scope.cancel() }
    }

    @Test fun restartCancelsPendingRequest() {
        val f = Fixture()
        var cancelled = false
        f.send = { try { awaitCancellation() } finally { cancelled = true } }
        try {
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("old")
            f.machine.startConversation()
            assertTrue(cancelled)
            f.send = { WebhookResult.Success(WebhookResponseBody(responseText = "new", outcome = WebhookOutcome.COMPLETED)) }
            f.capture.state.value = CaptureState.Finished("new")
            assertEquals(listOf("new"), f.tts.spoken)
            assertNotEquals(f.requests[0].second, f.requests[1].second)
        } finally { f.scope.cancel() }
    }

    @Test fun unconfirmedOutcomesNeverSpeakSuccess() {
        for (outcome in WebhookOutcome.entries.filter { it != WebhookOutcome.COMPLETED }) {
            val f = Fixture()
            try {
                f.send = { WebhookResult.Success(WebhookResponseBody(responseText = "Okay", outcome = outcome)) }
                f.machine.startConversation()
                f.capture.state.value = CaptureState.Finished("request")
                assertTrue(f.tts.spoken.isEmpty())
                assertTrue(f.machine.state.value is ConvoState.Error)
            } finally { f.scope.cancel() }
        }
    }

    @Test fun partialSalvageIsNotSentAndStorageFailureCanRecover() {
        val f = Fixture()
        try {
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("uncertain", requiresReview = true)
            assertTrue(f.requests.isEmpty())
            assertTrue(f.machine.state.value is ConvoState.Error)
            f.failStorage = true
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("request")
            assertTrue(f.machine.state.value is ConvoState.Error)
            f.failStorage = false
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("retry")
            assertEquals(listOf("reply"), f.tts.spoken)
        } finally { f.scope.cancel() }
    }

    @Test fun legacyOutputContinuesConversationWithoutVerifyingActionOutcome() {
        val f = Fixture()
        try {
            f.send = { WebhookResult.Success(parseResponseBody("""{"output":"Here is the answer"}""")) }
            f.machine.startConversation()
            f.capture.state.value = CaptureState.Finished("first question")
            assertEquals(listOf("Here is the answer"), f.tts.spoken)
            assertTrue((f.machine.state.value as ConvoState.Speaking).responseText.contains("action outcome not verified"))
            assertEquals("delivered", f.rows.single().status)
            assertEquals("Here is the answer", f.rows.single().responseText)
            f.tts.state.value = TtsState.Done(f.tts.id)
            assertEquals(ConvoState.Listening, f.machine.state.value)
            f.capture.state.value = CaptureState.Finished("follow-up")
            assertEquals(2, f.requests.size)
            assertEquals(f.requests.first().second, f.requests.last().second)
            assertEquals(listOf("Here is the answer", "Here is the answer"), f.tts.spoken)
        } finally { f.scope.cancel() }
    }

    @Test fun emptyAndExplicitNonCompletedRepliesNeverSpeakEvenWithLegacyFlag() {
        val responses = listOf(
            parseResponseBody(""),
            parseResponseBody("""{"output":" "}"""),
            parseResponseBody("""{"status":"completed","output":""}"""),
            parseResponseBody("""{"status":"accepted","output":"Done"}"""),
            parseResponseBody("""{"success":false,"output":"Done"}"""),
            parseResponseBody("""{"status":"unknown","output":"Done"}"""),
        ) + listOf(WebhookOutcome.ACCEPTED, WebhookOutcome.FAILED, WebhookOutcome.NEEDS_CONFIRMATION).map {
            WebhookResponseBody(responseText = "Done", outcome = it, isLegacyResponse = true)
        }
        for (response in responses) {
            val f = Fixture()
            try {
                f.send = { WebhookResult.Success(response) }
                f.machine.startConversation()
                f.capture.state.value = CaptureState.Finished("request")
                assertTrue(f.tts.spoken.isEmpty())
                assertTrue(f.machine.state.value is ConvoState.Error)
                assertEquals(1, f.capture.starts)
            } finally { f.scope.cancel() }
        }
    }
}
