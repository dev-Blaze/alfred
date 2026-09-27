package com.yshah.alfred.settings

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AuthScheme { NONE, BEARER, BASIC, CUSTOM_HEADER }

data class WebhookSettings(
    val webhookUrl: String = "",
    val authScheme: AuthScheme = AuthScheme.NONE,
    val authHeaderName: String = "Authorization",
    val authSecret: String = "",
    val credentialUnavailable: Boolean = false,
) {
    fun validate() {
        val url = webhookUrl.toHttpUrlOrNull()
        require(webhookUrl.none { it.isWhitespace() } && url != null && url.isHttps &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Enter an HTTPS webhook URL without embedded credentials or a fragment"
        }
        if (authScheme == AuthScheme.NONE) return
        require(!credentialUnavailable) { "Credential unavailable. Re-enter authentication and save." }
        require(authSecret.isNotBlank()) { "Enter an authentication secret" }
        require(authSecret.all { it.code in 32..126 }) { "Authentication must contain printable ASCII characters only" }
        if (authScheme == AuthScheme.BASIC) require(':' in authSecret) { "Basic authentication requires username:password" }
        if (authScheme == AuthScheme.CUSTOM_HEADER) {
            require(authHeaderName.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid HTTP header name" }
            require(authHeaderName.lowercase() !in setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "upgrade", "trailer", "te", "proxy-authorization")) {
                "This header is reserved for HTTP transport"
            }
        }
    }

    fun authHeaders(): Map<String, String> {
        validate()
        return when (authScheme) {
            AuthScheme.NONE -> emptyMap()
            AuthScheme.BEARER -> mapOf("Authorization" to "Bearer $authSecret")
            AuthScheme.BASIC -> mapOf("Authorization" to Credentials.basic(authSecret.substringBefore(':'), authSecret.substringAfter(':')))
            AuthScheme.CUSTOM_HEADER -> mapOf(authHeaderName to authSecret)
        }
    }
}
