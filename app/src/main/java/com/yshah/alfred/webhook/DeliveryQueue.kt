package com.yshah.alfred.webhook

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yshah.alfred.data.AlfredDatabase
import com.yshah.alfred.data.DeliveryEntity
import com.yshah.alfred.data.InteractionEntity
import com.yshah.alfred.network.WebhookClient
import com.yshah.alfred.network.WebhookResult
import com.yshah.alfred.network.WebhookOutcome
import com.yshah.alfred.network.WebhookRequestMetadata
import com.yshah.alfred.network.responseMetadata
import com.yshah.alfred.network.storedMetadata
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeliveryDependencies {
    fun database(): AlfredDatabase
    fun webhookClient(): WebhookClient
}

internal fun Context.deliveryDependencies() =
    EntryPointAccessors.fromApplication(this, DeliveryDependencies::class.java)

internal fun validateDelivery(item: DeliveryEntity) {
    item.requestMetadata().validate()
    require(item.requestId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid request ID" }
    require(item.type == "task" || item.type == "note") { "Invalid capture type" }
    require(item.text.isNotBlank() && item.text.length <= 50_000) { "Invalid capture text" }
    require(item.capturedAt > 0) { "Invalid capture timestamp" }
    ZoneId.of(item.timeZone)
    require(item.source == "phone" || item.source == "watch") { "Invalid capture source" }
}

internal fun requireSameCapture(existing: DeliveryEntity, incoming: DeliveryEntity) {
    require(existing.requestId == incoming.requestId && existing.text == incoming.text &&
        existing.type == incoming.type && existing.source == incoming.source &&
        existing.capturedAt == incoming.capturedAt && existing.timeZone == incoming.timeZone &&
        existing.conversationId == incoming.conversationId && existing.inReplyTo == incoming.inReplyTo &&
        existing.contextToken == incoming.contextToken) {
        "Request ID already belongs to another capture"
    }
}

internal fun DeliveryEntity.requestMetadata() = WebhookRequestMetadata(
    capturedAt = capturedAt, timeZone = timeZone, source = source, requestId = requestId,
    conversationId = conversationId, inReplyTo = inReplyTo, contextToken = contextToken)

internal fun followUp(original: InteractionEntity, text: String): DeliveryEntity {
    require(original.type in listOf("task", "note") && original.status !in listOf("pending", "sending")) { "This entry cannot receive a follow-up" }
    val metadata = responseMetadata(original.responseMetadata)
    return DeliveryEntity(java.util.UUID.randomUUID().toString(), text.trim(), original.type,
        System.currentTimeMillis(), ZoneId.systemDefault().id, "phone",
        conversationId = metadata.conversationId ?: original.conversationId,
        inReplyTo = original.sessionId, contextToken = metadata.clarification?.token).also(::validateDelivery)
}

object DeliveryQueue {
    suspend fun enqueue(context: Context, item: DeliveryEntity) = withContext(Dispatchers.IO) {
        validateDelivery(item)
        val manager = WorkManager.getInstance(context)
        // Register recovery before accepting data: a crash between Room and immediate scheduling is safe.
        manager.enqueueUniquePeriodicWork(
            "delivery-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeliveryRecoveryWorker>(15, TimeUnit.MINUTES).build(),
        ).result.get()
        val db = context.deliveryDependencies().database()
        db.withTransaction {
            val inserted = db.deliveryDao().insert(item) != -1L
            val existing = checkNotNull(db.deliveryDao().get(item.requestId))
            requireSameCapture(existing, item)
            if (inserted) record(db, existing)
        }
        // Persistence is acceptance; periodic recovery covers a failed immediate scheduling attempt.
        try { schedule(context) } catch (e: Exception) {
            android.util.Log.e("DeliveryQueue", "Immediate scheduling failed; recovery remains scheduled", e)
        }
    }

    suspend fun retry(context: Context, requestId: String, acknowledgePossibleDuplicate: Boolean) =
        withContext(Dispatchers.IO) {
            require(acknowledgePossibleDuplicate) { "The server may already have executed this request. Retrying can duplicate it." }
            val db = context.deliveryDependencies().database()
            db.withTransaction {
                check(db.deliveryDao().retry(requestId) == 1) { "Request is not retryable" }
                record(db, checkNotNull(db.deliveryDao().get(requestId)))
            }
            schedule(context)
        }

    internal fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "delivery-drain", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    internal suspend fun record(db: AlfredDatabase, item: DeliveryEntity) {
        db.interactionDao().upsert(InteractionEntity(
            sessionId = item.requestId, type = item.type, requestText = item.text,
            timestamp = item.capturedAt, status = item.status, responseText = item.message,
            httpCode = item.httpCode, timeZone = item.timeZone, source = item.source,
            conversationId = item.conversationId, inReplyTo = item.inReplyTo,
            contextToken = item.contextToken, responseMetadata = item.responseMetadata,
        ))
    }
}

class DeliveryRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        DeliveryQueue.schedule(applicationContext)
        return Result.success()
    }
}

class DeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val dependencies = applicationContext.deliveryDependencies()
                val db = dependencies.database()
                var publicationFailed = false
                for (item in db.deliveryDao().recoverable()) {
                    if (item.status == "pending") {
                        if (item.source == "watch") {
                            try { publish(item.requestId, "accepted", "Saved on phone", item.resultRevision) }
                            catch (e: Exception) { android.util.Log.w("DeliveryWorker", "ACK publication deferred", e) }
                        }
                        if (db.deliveryDao().claim(item.requestId) != 1) continue
                        // Once claimed, any interruption is uncertain. Never automatically replay mutations.
                        val outcome = try {
                            deliveryOutcome(dependencies.webhookClient().sendTaskOrNote(item.text, item.type, item.requestId,
                                item.requestMetadata()))
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { DeliveryOutcome("uncertain", e.message ?: "Delivery outcome unknown") }
                        finish(db, item, outcome)
                    } else if (item.status == "sending") {
                        finish(db, item, DeliveryOutcome("uncertain", "Delivery interrupted; the server may already have executed this request. Check before retrying."))
                    }
                    val updated = checkNotNull(db.deliveryDao().get(item.requestId))
                    if (updated.source == "watch" && !updated.resultPublished) {
                        try {
                            publish(updated.requestId, watchStatus(updated.status), updated.message ?: deliveryStatusLabel(updated.status), updated.resultRevision)
                            db.deliveryDao().published(updated.requestId, updated.resultRevision)
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            publicationFailed = true
                            android.util.Log.w("DeliveryWorker", "Result publication deferred", e)
                        }
                    }
                }
                if (publicationFailed) Result.retry() else Result.success()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.e("DeliveryWorker", "Delivery processing failed", e)
                Result.retry()
            }
        }
    }

    private suspend fun finish(db: AlfredDatabase, item: DeliveryEntity, outcome: DeliveryOutcome) {
        db.withTransaction {
            db.deliveryDao().finish(item.requestId, outcome.status, outcome.message, outcome.httpCode, outcome.metadata)
            DeliveryQueue.record(db, item.copy(status = outcome.status, message = outcome.message, httpCode = outcome.httpCode, responseMetadata = outcome.metadata))
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val intent = android.app.PendingIntent.getActivity(applicationContext, item.requestId.hashCode(),
                android.content.Intent(applicationContext, com.yshah.alfred.history.HistoryActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, WEBHOOK_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(com.yshah.alfred.R.drawable.ic_notification)
                .setContentTitle(deliveryStatusLabel(outcome.status))
                .setContentText(outcome.message ?: outcome.status)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(outcome.message ?: outcome.status))
                .setContentIntent(intent).setAutoCancel(true).build()
            androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(item.requestId.hashCode(), notification)
        }
    }

    private fun publish(id: String, status: String, message: String, revision: Long) {
        val request = PutDataMapRequest.create("/alfred/result/$id").apply {
            dataMap.putString("requestId", id)
            dataMap.putString("status", status)
            dataMap.putString("message", message.take(4000))
            dataMap.putLong("resultRevision", revision)
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(applicationContext).putDataItem(request), 30, TimeUnit.SECONDS)
    }

    companion object { private val lock = Mutex() }
}

internal data class DeliveryOutcome(val status: String, val message: String?, val httpCode: Int? = null, val metadata: String? = null)

internal fun deliveryOutcome(result: WebhookResult): DeliveryOutcome = when (result) {
    is WebhookResult.Success -> {
        val status = when (result.response.outcome) {
            WebhookOutcome.COMPLETED -> "completed"
            WebhookOutcome.ACCEPTED -> "accepted"
            WebhookOutcome.FAILED -> "failed"
            WebhookOutcome.NEEDS_CONFIRMATION -> "needs_confirmation"
            WebhookOutcome.UNKNOWN -> if (result.response.isLegacyResponse) "delivered" else "unknown"
        }
        val text = result.response.responseText ?: result.response.message
        DeliveryOutcome(status, listOfNotNull(deliveryStatusLabel(status), text?.takeIf { it.isNotBlank() }).joinToString("\n"), result.httpCode, result.response.storedMetadata())
    }
    is WebhookResult.HttpError -> DeliveryOutcome("http_error", result.body, result.code)
    is WebhookResult.Timeout -> DeliveryOutcome("uncertain", "Timed out; the server may already have executed this request. Check before retrying.")
    is WebhookResult.NetworkError -> DeliveryOutcome("uncertain", "Connection failed; the server may already have executed this request. Check before retrying.")
}

internal fun watchStatus(status: String): String = when (status) {
    "completed" -> "success"
    "pending", "sending", "accepted" -> "accepted"
    "http_error" -> "http_error"
    else -> "uncertain"
}

fun deliveryStatusLabel(status: String): String = when (status) {
    "pending" -> "Queued — waiting for connection"
    "sending" -> "Sending"
    "completed" -> "Completed"
    "accepted" -> "Server accepted — completion not confirmed"
    "delivered", "success" -> "Delivered — legacy response, completion not confirmed"
    "failed" -> "Server reported failure"
    "needs_confirmation" -> "Needs confirmation — review the server response"
    "http_error" -> "Server error — check before retrying"
    "uncertain", "unknown", "timeout", "network_error" -> "Outcome unknown — check before retrying"
    else -> status.replace('_', ' ')
}
