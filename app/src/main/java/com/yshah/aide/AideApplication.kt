package com.yshah.aide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yshah.aide.webhook.WEBHOOK_NOTIFICATION_CHANNEL_ID
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            WEBHOOK_NOTIFICATION_CHANNEL_ID,
            "Webhook results",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Task/note delivery status from Aide's n8n webhook"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
