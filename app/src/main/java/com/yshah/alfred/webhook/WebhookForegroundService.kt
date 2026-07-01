package com.yshah.alfred.webhook

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yshah.alfred.R
import com.yshah.alfred.data.InteractionDao
import com.yshah.alfred.data.InteractionEntity
import com.yshah.alfred.history.HistoryActivity
import com.yshah.alfred.network.WebhookClient
import com.yshah.alfred.network.WebhookResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

const val WEBHOOK_NOTIFICATION_CHANNEL_ID = "alfred_webhook_channel"
private const val RUNNING_NOTIFICATION_ID = 1001

/**
 * Started from inside the still-visible overlay (before it dismisses) so the background-service
 * start is covered by the visible-transition exemption rather than risking
 * ForegroundServiceStartNotAllowedException — see the plan's foreground-service note. `dataSync`
 * matches "processing/transferring data over network" and has no Play-review requirement (moot
 * for a sideloaded personal app anyway).
 */
@AndroidEntryPoint
class WebhookForegroundService : Service() {

    companion object {
        private const val ACTION_SEND_TASK_OR_NOTE = "com.yshah.alfred.action.SEND_TASK_OR_NOTE"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_SESSION_ID = "extra_session_id"

        fun start(context: Context, text: String, type: String, sessionId: String) {
            val intent = Intent(context, WebhookForegroundService::class.java).apply {
                action = ACTION_SEND_TASK_OR_NOTE
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    @Inject lateinit var webhookClient: WebhookClient
    @Inject lateinit var interactionDao: InteractionDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val type = intent?.getStringExtra(EXTRA_TYPE).orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()

        // minSdk 34 already guarantees the typed 3-arg overload (added API 29) is available.
        startForeground(
            RUNNING_NOTIFICATION_ID,
            buildRunningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        serviceScope.launch {
            val result = webhookClient.sendTaskOrNote(text = text, type = type, sessionId = sessionId)
            recordInteraction(sessionId, type, text, result)
            showResultNotification(sessionId, type, result)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildRunningNotification(): Notification =
        NotificationCompat.Builder(this, WEBHOOK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sending to Alfred")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private suspend fun recordInteraction(sessionId: String, type: String, text: String, result: WebhookResult) {
        val (status, responseText) = when (result) {
            is WebhookResult.Success -> "success" to (result.response.responseText ?: result.response.message)
            is WebhookResult.HttpError -> "http_error" to result.body
            is WebhookResult.Timeout -> "timeout" to null
            is WebhookResult.NetworkError -> "network_error" to result.throwable.message
        }
        interactionDao.upsert(
            InteractionEntity(
                sessionId = sessionId,
                type = type,
                requestText = text,
                timestamp = System.currentTimeMillis(),
                status = status,
                responseText = responseText,
            ),
        )
    }

    private fun showResultNotification(sessionId: String, type: String, result: WebhookResult) {
        val label = if (type == "task") "Task" else "Note"
        val (title, body) = when (result) {
            is WebhookResult.Success -> "$label sent" to (result.response.responseText ?: result.response.message ?: "Delivered")
            is WebhookResult.HttpError -> "$label failed" to "Server error (HTTP ${result.code})"
            is WebhookResult.Timeout -> "$label timed out" to "No response within the timeout"
            is WebhookResult.NetworkError -> "$label failed" to (result.throwable.message ?: "Network error")
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            sessionId.hashCode(),
            Intent(this, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, WEBHOOK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val hasNotificationPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasNotificationPermission) {
            NotificationManagerCompat.from(this).notify(sessionId.hashCode(), notification)
        }
    }
}
