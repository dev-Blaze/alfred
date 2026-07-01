package com.yshah.alfred

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yshah.alfred.webhook.WEBHOOK_NOTIFICATION_CHANNEL_ID
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AlfredApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            WEBHOOK_NOTIFICATION_CHANNEL_ID,
            "Webhook results",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Task/note delivery status from Alfred's n8n webhook"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
