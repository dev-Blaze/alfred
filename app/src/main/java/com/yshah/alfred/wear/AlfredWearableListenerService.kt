package com.yshah.alfred.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.PutDataMapRequest
import com.yshah.alfred.webhook.WebhookForegroundService
import com.yshah.alfred.webhook.deliveryDependencies
import com.yshah.alfred.webhook.watchStatus
import com.yshah.alfred.webhook.deliveryStatusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.time.ZoneId

internal fun watchCaptureTime(capturedAt: Long?, timestamp: Long?, timeZone: String?): Pair<Long, String> {
    val time = capturedAt ?: timestamp
    require(time != null && time > 0) { "Missing or invalid capture timestamp" }
    // Legacy watches supplied only epoch milliseconds. UTC is deterministic across retries/devices.
    val zone = timeZone ?: if (capturedAt == null) "UTC" else error("Missing capture timezone")
    require(zone.isNotBlank()) { "Invalid capture timezone" }
    ZoneId.of(zone)
    return time to zone
}

class AlfredWearableListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // This callback runs on a background thread. Complete durable acceptance before returning.
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val uri = event.dataItem.uri
            val path = uri.path.orEmpty()
            if (!path.startsWith("/alfred/capture/")) return@forEach
            try {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val id = map.getString("requestId") ?: map.getString("sessionId").orEmpty()
                require(path == "/alfred/capture/$id") { "Capture path and ID differ" }
                val (capturedAt, timeZone) = watchCaptureTime(
                    if (map.containsKey("capturedAt")) map.getLong("capturedAt") else null,
                    if (map.containsKey("timestamp")) map.getLong("timestamp") else null,
                    map.getString("timeZone"),
                )
                val saved = runBlocking(Dispatchers.IO) {
                    WebhookForegroundService.enqueue(
                        context = this@AlfredWearableListenerService,
                        text = map.getString("text").orEmpty(), type = map.getString("type").orEmpty(),
                        sessionId = id, capturedAt = capturedAt,
                        timeZone = timeZone, source = "watch",
                    )
                    checkNotNull(deliveryDependencies().database().deliveryDao().get(id))
                }
                val acknowledgment = PutDataMapRequest.create("/alfred/result/$id").apply {
                    dataMap.putString("requestId", id)
                    dataMap.putString("status", watchStatus(saved.status))
                    dataMap.putLong("resultRevision", saved.resultRevision)
                    dataMap.putString("message", saved.message?.take(4000) ?: if (saved.status in listOf("pending", "sending")) "Saved on phone" else deliveryStatusLabel(saved.status))
                }.asPutDataRequest().setUrgent()
                Tasks.await(Wearable.getDataClient(this).putDataItem(acknowledgment), 30, TimeUnit.SECONDS)
                runBlocking(Dispatchers.IO) {
                    deliveryDependencies().database().deliveryDao().republish(id)
                }
                com.yshah.alfred.webhook.DeliveryQueue.schedule(this)
                Tasks.await(Wearable.getDataClient(this).deleteDataItems(uri), 30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Keep the capture on the Data Layer if validation or durable acceptance fails.
                Log.e("AlfredWearableListener", "Capture not consumed", e)
            }
        }
    }
}
