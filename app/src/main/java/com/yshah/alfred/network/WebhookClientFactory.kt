package com.yshah.alfred.network

import com.yshah.alfred.settings.SecureSettingsStore
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Two timeout profiles sharing one connection pool/dispatcher: a 300s "fire and (eventually)
 * notify" profile for task/note/image, and a short ~20s profile for convo mode, which runs
 * in-session while the user is actively waiting for a spoken reply — see the plan's convo-mode
 * timeout-tension note for why these must not share one timeout.
 */
class WebhookClientFactory(@Suppress("UNUSED_PARAMETER") settingsStore: SecureSettingsStore? = null) {
    private val sharedPool = ConnectionPool()
    private val sharedDispatcher = Dispatcher()

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionPool(sharedPool)
        .dispatcher(sharedDispatcher)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Bound successful and error bodies before Retrofit can buffer them.
            val body = response.body
            val contentType = body.contentType()
            response.newBuilder().body(body.readBounded().toResponseBody(contentType)).build()
        }

    val longRunningClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val convoClient: OkHttpClient by lazy {
        baseBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
