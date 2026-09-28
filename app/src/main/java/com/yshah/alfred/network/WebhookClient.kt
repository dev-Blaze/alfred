package com.yshah.alfred.network

import com.yshah.alfred.settings.SecureSettingsStore
import com.yshah.alfred.settings.WebhookSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant

sealed class WebhookResult {
    // Success means HTTP delivery; response.outcome describes the business result.
    data class Success(val response: WebhookResponseBody, val httpCode: Int = 200) : WebhookResult()
    data class HttpError(val code: Int, val body: String?) : WebhookResult()
    data class Timeout(val elapsedMs: Long) : WebhookResult()
    data class NetworkError(val throwable: Throwable) : WebhookResult()
}

interface WebhookClient {
    suspend fun sendTaskOrNote(text: String, type: String, sessionId: String): WebhookResult
    suspend fun sendTaskOrNote(text: String, type: String, sessionId: String, metadata: WebhookRequestMetadata): WebhookResult =
        sendTaskOrNote(text, type, sessionId)
    suspend fun sendConvoTurn(text: String, sessionId: String): WebhookResult =
        sendConvoTurn(text, sessionId, WebhookRequestMetadata(conversationId = sessionId))
    suspend fun sendConvoTurn(text: String, sessionId: String, metadata: WebhookRequestMetadata): WebhookResult
    suspend fun testConnection(): ConnectionTestResult
    suspend fun testConnection(settings: WebhookSettings): ConnectionTestResult =
        ConnectionTestResult.Failure("Draft connection testing is not supported by this client")
}

class RetrofitWebhookClient(
    private val settingsStore: SecureSettingsStore,
    private val clientFactory: WebhookClientFactory,
    private val intentClassifier: IntentClassifier? = null,
) : WebhookClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun apiFor(client: OkHttpClient): AlfredWebhookApi = Retrofit.Builder()
        .baseUrl("https://alfred.invalid/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(AlfredWebhookApi::class.java)

    override suspend fun sendTaskOrNote(text: String, type: String, sessionId: String): WebhookResult =
        sendTaskOrNote(text, type, sessionId, WebhookRequestMetadata(requestId = sessionId))

    override suspend fun sendTaskOrNote(text: String, type: String, sessionId: String, metadata: WebhookRequestMetadata): WebhookResult =
        executeCall {
            require(type == "task" || type == "note") { "Invalid capture type" }
            send(settingsStore.currentSettingsSnapshot(), clientFactory.longRunningClient, text, type, sessionId, metadata)
        }

    override suspend fun sendConvoTurn(text: String, sessionId: String, metadata: WebhookRequestMetadata): WebhookResult = executeCall {
        send(settingsStore.currentSettingsSnapshot(), clientFactory.convoClient, text, "convo", sessionId, metadata)
    }

    private suspend fun send(settings: WebhookSettings, client: OkHttpClient, text: String, type: String,
                             sessionId: String, metadata: WebhookRequestMetadata): Response<ResponseBody> {
        val headers = settings.authHeaders() // One immutable URL/auth snapshot, validated before any I/O.
        metadata.validate()
        require(type == "ping" || (text.isNotBlank() && text.length <= 50_000)) { "Invalid capture text" }
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid session ID" }
        val hint = if (type == "ping") null else intentClassifier?.classify(text, metadata.inReplyTo != null)
        val routedType = if (type == "convo") automaticCaptureType(hint) else type
        val payload = WebhookJsonPayload(routedType, text, Instant.ofEpochMilli(metadata.capturedAt).toString(), sessionId,
            metadata.capturedAt, metadata.timeZone, metadata.source, metadata.requestId, metadata.conversationId, metadata.schemaVersion,
            metadata.inReplyTo, metadata.contextToken,
            hint)
        return apiFor(client).sendJson(settings.webhookUrl, payload, headers)
    }

    override suspend fun testConnection(): ConnectionTestResult = testConnection(settingsStore.currentSettingsSnapshot())

    override suspend fun testConnection(settings: WebhookSettings): ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            val response = send(settings, clientFactory.convoClient, "", "ping", "test", WebhookRequestMetadata())
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                ConnectionTestResult.Failure("Server responded with HTTP ${response.code()}")
            } else validateCapabilityPing(response.body()?.readBounded().orEmpty(), response.code())
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            ConnectionTestResult.Failure(if (e is IllegalArgumentException) e.message ?: "Invalid settings" else "Connection or capability validation failed")
        }
    }

    private suspend fun executeCall(block: suspend () -> Response<ResponseBody>): WebhookResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val response = block()
            if (response.isSuccessful) WebhookResult.Success(parseResponseBody(response.body()?.readBounded().orEmpty()), response.code())
            else WebhookResult.HttpError(response.code(), response.errorBody()?.readBounded())
        } catch (e: CancellationException) { throw e
        } catch (e: InterruptedIOException) {
            WebhookResult.Timeout((System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) { WebhookResult.NetworkError(e) }
    }
}

internal const val MAX_RESPONSE_BYTES = 1_048_576L

internal fun automaticCaptureType(hint: String?): String = when (hint) {
    "action" -> "task"
    "capture" -> "note"
    else -> "convo" // Questions, mixed intents and uncertain follow-ups retain dialogue context.
}

internal fun ResponseBody.readBounded(): String = use {
    val source = source()
    if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Webhook response exceeds 1 MiB")
    source.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}

private val responseJson = Json { ignoreUnknownKeys = true }
private fun parseJson(text: String): JsonElement? = try { responseJson.parseToJsonElement(text) } catch (_: IllegalArgumentException) { null }
private fun JsonElement.responseObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonArray -> firstOrNull()?.responseObject()
    else -> null
}

internal fun parseResponseBody(body: String): WebhookResponseBody {
    if (body.isBlank()) return WebhookResponseBody(outcome = WebhookOutcome.ACCEPTED)
    val element = parseJson(body)
    val event = element as? JsonObject
    when ((event?.get("type") as? JsonPrimitive)?.content) {
        "error" -> return WebhookResponseBody(message = (event["message"] as? JsonPrimitive)?.content ?: body, outcome = WebhookOutcome.FAILED)
        "item" -> event["content"]?.let { content ->
            return parseResponseBody(if (content is JsonPrimitive && content.isString) content.content else content.toString())
        }
    }
    if (element == null) {
        val events = body.lineSequence().mapNotNull { parseJson(it.trim()) as? JsonObject }.toList()
        val last = events.lastOrNull { (it["type"] as? JsonPrimitive)?.content in setOf("item", "error") }
        if (last != null) {
            if ((last["type"] as? JsonPrimitive)?.content == "error")
                return WebhookResponseBody(message = (last["message"] as? JsonPrimitive)?.content ?: last.toString(), outcome = WebhookOutcome.FAILED)
            val content = last["content"]
            if (content != null) return parseResponseBody(if (content is JsonPrimitive && content.isString) content.content else content.toString())
        }
    }
    val obj = element?.responseObject()
    val status = (obj?.get("status") as? JsonPrimitive)?.content
    val error = obj?.get("error")?.takeUnless { it is JsonNull || it == JsonPrimitive(false) || it == JsonPrimitive("") }
    val outcome = if (error != null || obj?.get("success") == JsonPrimitive(false)) WebhookOutcome.FAILED else when (status?.lowercase()) {
        "accepted" -> WebhookOutcome.ACCEPTED
        "completed" -> WebhookOutcome.COMPLETED
        "failed", "error" -> WebhookOutcome.FAILED
        "needs_confirmation" -> WebhookOutcome.NEEDS_CONFIRMATION
        else -> WebhookOutcome.UNKNOWN
    }
    val text = listOf("responseText", "output", "text", "response", "message", "reply")
        .firstNotNullOfOrNull { (obj?.get(it) as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        ?: (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    val response = WebhookResponseBody(responseText = text, status = status,
        outcome = outcome,
        isLegacyResponse = status == null && error == null && obj?.get("success") != JsonPrimitive(false),
        receipt = (obj?.get("receipt") as? JsonObject)?.let { receipt ->
            receipt.boundedString("action", 128)?.let { action ->
                ActionReceipt(action, receipt.boundedString("externalId", 512), safeReceiptUrl(receipt.boundedString("url", 2048)))
            }
        },
        clarification = (obj?.get("clarification") as? JsonObject)?.let { clarification ->
            val token = clarification.boundedString("token", 2048)?.takeIf(::validOpaqueToken)
            val question = clarification.boundedString("question", 4000)
            if (token != null && question != null) Clarification(token, question) else null
        },
        conversationId = obj?.boundedString("conversationId", 128)?.takeIf(::validCorrelationId))
    return response.copy(message = if (text == null) {
        if (error != null) body else response.clarification?.question ?: response.receipt?.action ?: body
    } else null)
}

private fun JsonObject.boundedString(key: String, max: Int): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() && it.length <= max && it.none { char -> char.isISOControl() && char != '\n' && char != '\t' } }

// Contract: {"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}.
internal fun validateCapabilityPing(body: String, code: Int): ConnectionTestResult {
    val obj = parseJson(body) as? JsonObject
    val capabilities = obj?.get("capabilities") as? JsonArray
    return if ((obj?.get("type") as? JsonPrimitive)?.content == "pong" &&
        obj["schemaVersion"] == JsonPrimitive(1) &&
        capabilities != null && capabilities.all { it is JsonPrimitive && it.isString } &&
        capabilities.map { (it as JsonPrimitive).content }.containsAll(listOf("task", "note", "convo")) &&
        (obj["status"] == null || (obj["status"] as? JsonPrimitive)?.content in setOf("accepted", "completed")) &&
        parseResponseBody(body).outcome !in setOf(WebhookOutcome.FAILED, WebhookOutcome.NEEDS_CONFIRMATION)) {
        ConnectionTestResult.Success(code)
    } else ConnectionTestResult.Failure("HTTP $code reached, but capabilities are unverified. A legacy backend may still work; you can save these settings. Verification requires pong/schemaVersion 1 and task, note, convo capabilities.")
}
