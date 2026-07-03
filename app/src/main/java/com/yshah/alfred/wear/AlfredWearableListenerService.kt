package com.yshah.alfred.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.yshah.alfred.webhook.WebhookForegroundService
import dagger.hilt.android.AndroidEntryPoint

/**
 * Receives Task/Note captures relayed from the Wear OS companion app (a separate app/repo —
 * see alfred-companion) over the Data Layer API. Bound by Play Services via the
 * BIND_LISTENER manifest intent-filter, which wakes this app's process even if it isn't
 * currently running — the same mechanism FCM relies on — so watch captures are delivered
 * whether or not the phone app happens to be open.
 *
 * Deliberately calls the exact same WebhookForegroundService.start(...) entry point a
 * phone-originated task/note uses — no parallel webhook/notification/history path.
 */
@AndroidEntryPoint
class AlfredWearableListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path?.startsWith("/alfred/capture") == true
            ) {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                WebhookForegroundService.start(
                    context = this,
                    text = map.getString("text").orEmpty(),
                    type = map.getString("type").orEmpty(),
                    sessionId = map.getString("sessionId").orEmpty(),
                )
                // Consume the item — otherwise it re-fires onDataChanged on every future reconnect.
                Wearable.getDataClient(this).deleteDataItems(event.dataItem.uri)
            }
        }
        dataEvents.release()
    }
}
