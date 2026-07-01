package com.yshah.aide.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.webhookDataStore by preferencesDataStore(name = "aide_webhook_settings")

/**
 * Webhook URL is plaintext (not a credential); the auth secret is AES-256-GCM encrypted via
 * Tink, backed by an Android Keystore-protected key — not EncryptedSharedPreferences, which
 * Google deprecated (still functional, no longer recommended for new code) as of April 2025.
 */
class SecureSettingsStore(private val context: Context) {
    private object Keys {
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val AUTH_SCHEME = stringPreferencesKey("auth_scheme")
        val AUTH_HEADER_NAME = stringPreferencesKey("auth_header_name")
        val AUTH_SECRET_ENCRYPTED = stringPreferencesKey("auth_secret_encrypted")
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "aide_webhook_keyset", "aide_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://aide_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    val settings: Flow<WebhookSettings> = context.webhookDataStore.data.map { prefs ->
        WebhookSettings(
            webhookUrl = prefs[Keys.WEBHOOK_URL] ?: "",
            authScheme = AuthScheme.entries.find { it.name == prefs[Keys.AUTH_SCHEME] } ?: AuthScheme.NONE,
            authHeaderName = prefs[Keys.AUTH_HEADER_NAME] ?: "Authorization",
            authSecret = decrypt(prefs[Keys.AUTH_SECRET_ENCRYPTED]),
        )
    }

    suspend fun currentSettingsSnapshot(): WebhookSettings = settings.first()

    suspend fun updateWebhookUrl(url: String) {
        context.webhookDataStore.edit { it[Keys.WEBHOOK_URL] = url }
    }

    suspend fun updateAuth(scheme: AuthScheme, headerName: String, secret: String) {
        context.webhookDataStore.edit { prefs ->
            prefs[Keys.AUTH_SCHEME] = scheme.name
            prefs[Keys.AUTH_HEADER_NAME] = headerName
            prefs[Keys.AUTH_SECRET_ENCRYPTED] = encrypt(secret)
        }
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val ciphertext = aead.encrypt(plain.toByteArray(Charsets.UTF_8), null)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            val ciphertext = Base64.decode(stored, Base64.NO_WRAP)
            String(aead.decrypt(ciphertext, null), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
