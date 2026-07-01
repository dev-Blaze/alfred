package com.yshah.alfred.network

import com.yshah.alfred.settings.SecureSettingsStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

sealed class WebhookResult {
    data class Success(val response: WebhookResponseBody) : WebhookResult()
    data class HttpError(val code: Int, val body: String?) : WebhookResult()
    data class Timeout(val elapsedMs: Long) : WebhookResult()
    data class NetworkError(val throwable: Throwable) : WebhookResult()
}

// Common n8n response shapes seen in practice: a plain object with one of these fields, or (very
// common for AI Agent nodes) an array wrapping one object with an "output" field, e.g.
// `[{"output": "..."}]`. Checked in order; first string match wins.
private val RESPONSE_TEXT_KEYS = listOf("responseText", "output", "text", "response", "message", "reply")

/**
 * Transport-agnostic of the caller's execution context: a plain suspend-fun interface with no
 * callbacks, so it can be invoked from a foreground service's coroutine scope (task/note), a
 * ViewModel (convo), or anywhere else — see the plan's handoff-contract note.
 *
 * Image sending (sendImage/multipart) was removed along with Camera mode — see the plan's
 * "Camera mode deferred" note. n8n received each multipart part as a separate binary file
 * instead of form fields; re-check Content-Disposition/part naming before re-adding.
 */
interface WebhookClient {
    suspend fun sendTaskOrNote(text: String, type: String, sessionId: String): WebhookResult
    suspend fun sendConvoTurn(text: String, sessionId: String): WebhookResult
    suspend fun testConnection(): ConnectionTestResult
}

class RetrofitWebhookClient(
    private val settingsStore: SecureSettingsStore,
    private val clientFactory: WebhookClientFactory,
) : WebhookClient {

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiFor(okHttpClient: OkHttpClient): AlfredWebhookApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            // Actual destination is supplied per-call via @Url; this is never used as-is.
            .baseUrl("https://alfred.invalid/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(AlfredWebhookApi::class.java)
    }

    override suspend fun sendTaskOrNote(text: String, type: String, sessionId: String): WebhookResult =
        executeCall {
            val settings = settingsStore.currentSettingsSnapshot()
            val payload = WebhookJsonPayload(type = type, text = text, timestamp = nowIso(), sessionId = sessionId)
            apiFor(clientFactory.longRunningClient).sendJson(settings.webhookUrl, payload)
        }

    override suspend fun sendConvoTurn(text: String, sessionId: String): WebhookResult =
        executeCall {
            val settings = settingsStore.currentSettingsSnapshot()
            val payload = WebhookJsonPayload(type = "convo", text = text, timestamp = nowIso(), sessionId = sessionId)
            apiFor(clientFactory.convoClient).sendJson(settings.webhookUrl, payload)
        }

    override suspend fun testConnection(): ConnectionTestResult {
        val settings = settingsStore.currentSettingsSnapshot()
        if (settings.webhookUrl.isBlank()) {
            return ConnectionTestResult.Failure("Set a webhook URL first")
        }
        return try {
            val payload = WebhookJsonPayload(type = "ping", text = "", timestamp = nowIso(), sessionId = "test")
            val response = apiFor(clientFactory.convoClient).sendJson(settings.webhookUrl, payload)
            if (response.isSuccessful) {
                ConnectionTestResult.Success(response.code())
            } else {
                ConnectionTestResult.Failure("Server responded with HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            ConnectionTestResult.Failure(e.message ?: "Connection failed")
        }
    }

    /**
     * n8n webhooks commonly reply with an empty body (no "Respond to Webhook" node), plain text,
     * a JSON shape our data class doesn't match exactly, or — confirmed against a real AI Agent
     * workflow with streaming enabled — newline-delimited JSON "events"
     * (`{"type":"begin"/"item"/"end", "content": ..., "metadata": {...}}` per token/node), where
     * the actual answer is the *last* "item" event's `content`, itself often JSON-encoded again
     * (e.g. `content: "{\"output\": \"...\"}"`). Any 2xx is still a Success.
     */
    private suspend fun executeCall(block: suspend () -> Response<okhttp3.ResponseBody>): WebhookResult {
        val start = System.currentTimeMillis()
        return try {
            val response = block()
            if (response.isSuccessful) {
                val bodyString = response.body()?.string().orEmpty()
                WebhookResult.Success(parseResponseBody(bodyString))
            } else {
                WebhookResult.HttpError(response.code(), response.errorBody()?.string())
            }
        } catch (e: SocketTimeoutException) {
            WebhookResult.Timeout(elapsedMs = System.currentTimeMillis() - start)
        } catch (e: IOException) {
            WebhookResult.NetworkError(e)
        } catch (e: Exception) {
            WebhookResult.NetworkError(e)
        }
    }

    private fun parseResponseBody(bodyString: String): WebhookResponseBody {
        if (bodyString.isBlank()) return WebhookResponseBody()
        val text = extractResponseTextFromSingleJson(bodyString)
            ?: extractResponseTextFromNdjsonStream(bodyString)
        return if (text != null) WebhookResponseBody(responseText = text) else WebhookResponseBody(message = bodyString.take(500))
    }

    private fun extractResponseTextFromSingleJson(bodyString: String): String? = try {
        extractResponseText(json.parseToJsonElement(bodyString))
    } catch (e: SerializationException) {
        null
    }

    private fun extractResponseTextFromNdjsonStream(bodyString: String): String? {
        val lastItemContent = bodyString.lineSequence()
            .mapNotNull { line ->
                try {
                    json.parseToJsonElement(line.trim()) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
            }
            .filter { (it["type"] as? JsonPrimitive)?.content == "item" }
            .lastOrNull()
            ?.get("content") as? JsonPrimitive ?: return null

        // The event's content is itself often JSON-encoded (e.g. a stringified {"output": "..."})
        // rather than plain text — try parsing it again before falling back to it as-is.
        val content = lastItemContent.content
        return extractResponseTextFromSingleJson(content) ?: content
    }

    private fun extractResponseText(element: JsonElement): String? {
        val obj = when (element) {
            is JsonArray -> element.firstOrNull() as? JsonObject
            is JsonObject -> element
            else -> null
        } ?: return null
        for (key in RESPONSE_TEXT_KEYS) {
            val value = obj[key] as? JsonPrimitive
            if (value != null && value.isString) return value.content
        }
        return null
    }

    private fun nowIso(): String = Instant.now().toString()
}
