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
) {
    fun validate() {
        require(capturedAt > 0 && schemaVersion == 1) { "Invalid request metadata" }
        Instant.ofEpochMilli(capturedAt)
        ZoneId.of(timeZone)
        require(source == "phone" || source == "watch") { "Invalid request source" }
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid request ID" }
        require(conversationId == null || conversationId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid conversation ID" }
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
)
