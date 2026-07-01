package com.yshah.aide.settings

enum class AuthScheme { NONE, BEARER, BASIC, CUSTOM_HEADER }

data class WebhookSettings(
    val webhookUrl: String = "",
    val authScheme: AuthScheme = AuthScheme.NONE,
    val authHeaderName: String = "Authorization",
    val authSecret: String = "",
)
