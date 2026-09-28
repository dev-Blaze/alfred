package com.yshah.alfred.network

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class WebhookRequestMetadata(
    val capturedAt: Long = System.currentTimeMillis(),
    val timeZone: String = ZoneId.systemDefault().id,
    val source: String = "phone",
    val requestId: String = UUID.randomUUID().toString(),
    val conversationId: String? = null,
    val schemaVersion: Int = 1,
    val inReplyTo: String? = null,
    val contextToken: String? = null,
) {
    fun validate() {
        require(capturedAt > 0 && schemaVersion == 1) { "Invalid request metadata" }
        Instant.ofEpochMilli(capturedAt)
        ZoneId.of(timeZone)
        require(source == "phone" || source == "watch") { "Invalid request source" }
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid request ID" }
        require(conversationId == null || conversationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid conversation ID" }
        require(inReplyTo == null || (inReplyTo != requestId && validCorrelationId(inReplyTo))) { "Invalid reply ID" }
        require(contextToken == null || (inReplyTo != null && validOpaqueToken(contextToken))) { "Invalid context token" }
    }
}

@Serializable
data class WebhookJsonPayload(
    val type: String, // "task" | "note" | "convo" | "ping" — lets the n8n workflow branch
    val text: String,
    val timestamp: String,
    val sessionId: String,
    val capturedAt: Long = System.currentTimeMillis(),
    val timeZone: String = ZoneId.systemDefault().id,
    val source: String = "phone",
    val requestId: String = sessionId,
    val conversationId: String? = null,
    val schemaVersion: Int = 1,
    val inReplyTo: String? = null,
    val contextToken: String? = null,
    val intentHint: String? = null,
)

@Serializable
enum class WebhookOutcome { ACCEPTED, COMPLETED, FAILED, NEEDS_CONFIRMATION, UNKNOWN }

@Serializable
data class WebhookResponseBody(
    val responseText: String? = null,
    val status: String? = null,
    val message: String? = null,
    val outcome: WebhookOutcome = WebhookOutcome.UNKNOWN,
    val isLegacyResponse: Boolean = false,
    val receipt: ActionReceipt? = null,
    val clarification: Clarification? = null,
    val conversationId: String? = null,
)

@Serializable
data class ActionReceipt(val action: String, val externalId: String? = null, val url: String? = null)

@Serializable
data class Clarification(val token: String, val question: String)

internal fun validCorrelationId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
internal fun validOpaqueToken(value: String) = value.isNotBlank() && value.length <= 2048 && value.none { it.isISOControl() }

fun safeReceiptUrl(value: String?): String? {
    if (value == null || value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
    return try {
        val uri = java.net.URI(value)
        value.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535) }
    } catch (_: java.net.URISyntaxException) { null }
}

private val metadataJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
fun WebhookResponseBody.storedMetadata(): String? =
    if (receipt == null && clarification == null && conversationId == null) null
    else metadataJson.encodeToString(copy(responseText = null, status = null, message = null, outcome = WebhookOutcome.UNKNOWN, isLegacyResponse = false))

fun responseMetadata(value: String?): WebhookResponseBody = parseResponseBody(value ?: "")
