package com.yshah.aide.network

import com.yshah.aide.settings.SecureSettingsStore
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Two timeout profiles sharing one connection pool/dispatcher: a 300s "fire and (eventually)
 * notify" profile for task/note/image, and a short ~20s profile for convo mode, which runs
 * in-session while the user is actively waiting for a spoken reply — see the plan's convo-mode
 * timeout-tension note for why these must not share one timeout.
 */
class WebhookClientFactory(settingsStore: SecureSettingsStore) {
    private val sharedPool = ConnectionPool()
    private val sharedDispatcher = Dispatcher()
    private val authInterceptor = AuthHeaderInterceptor(settingsStore)

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionPool(sharedPool)
        .dispatcher(sharedDispatcher)
        .addInterceptor(authInterceptor)

    val longRunningClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val convoClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
