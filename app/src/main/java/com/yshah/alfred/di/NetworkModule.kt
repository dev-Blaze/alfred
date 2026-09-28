package com.yshah.alfred.di

import android.content.Context
import com.yshah.alfred.network.RetrofitWebhookClient
import com.yshah.alfred.network.WebhookClient
import com.yshah.alfred.network.WebhookClientFactory
import com.yshah.alfred.settings.SecureSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideSecureSettingsStore(@ApplicationContext context: Context): SecureSettingsStore =
        SecureSettingsStore(context)

    @Provides
    @Singleton
    fun provideWebhookClientFactory(settingsStore: SecureSettingsStore): WebhookClientFactory =
        WebhookClientFactory(settingsStore)

    @Provides
    @Singleton
    fun provideWebhookClient(
        settingsStore: SecureSettingsStore,
        clientFactory: WebhookClientFactory,
        @ApplicationContext context: Context,
    ): WebhookClient = RetrofitWebhookClient(settingsStore, clientFactory,
        com.yshah.alfred.network.IntentClassifier(context))
}
