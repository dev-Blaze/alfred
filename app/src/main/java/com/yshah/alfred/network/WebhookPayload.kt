package com.yshah.alfred.network

import kotlinx.serialization.Serializable

@Serializable
data class WebhookJsonPayload(
    val type: String, // "task" | "note" | "convo" | "ping" — lets the n8n workflow branch
    val text: String,
    val timestamp: String,
    val sessionId: String,
)

@Serializable
data class WebhookResponseBody(
    val responseText: String? = null,
    val status: String? = null,
    val message: String? = null,
)
