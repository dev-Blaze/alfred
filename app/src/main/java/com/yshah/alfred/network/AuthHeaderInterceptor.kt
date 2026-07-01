package com.yshah.alfred.network

import com.yshah.alfred.settings.AuthScheme
import com.yshah.alfred.settings.SecureSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

class AuthHeaderInterceptor(private val settingsStore: SecureSettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val settings = runBlocking { settingsStore.currentSettingsSnapshot() }
        val request = chain.request().newBuilder().apply {
            when (settings.authScheme) {
                AuthScheme.BEARER -> addHeader("Authorization", "Bearer ${settings.authSecret}")
                AuthScheme.BASIC -> {
                    val parts = settings.authSecret.split(":", limit = 2)
                    val user = parts.getOrElse(0) { "" }
                    val pass = parts.getOrElse(1) { "" }
                    addHeader("Authorization", Credentials.basic(user, pass))
                }
                AuthScheme.CUSTOM_HEADER -> addHeader(settings.authHeaderName, settings.authSecret)
                AuthScheme.NONE -> {}
            }
        }.build()
        return chain.proceed(request)
    }
}
