package com.yshah.alfred.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.data.InteractionDao
import com.yshah.alfred.data.InteractionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import android.content.Context
import android.net.Uri
import com.yshah.alfred.data.AlfredDatabase
import com.yshah.alfred.webhook.DeliveryQueue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    interactionDao: InteractionDao,
    private val database: AlfredDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val interactions: StateFlow<List<InteractionEntity>> = interactionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val retryableIds = database.deliveryDao().observeRetryableIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val replySaved = MutableStateFlow<String?>(null)

    private var replyDraft: com.yshah.alfred.data.DeliveryEntity? = null

    fun reply(original: InteractionEntity, text: String) = perform {
        val item = replyDraft?.takeIf { it.inReplyTo == original.sessionId && it.text == text.trim() }
            ?: com.yshah.alfred.webhook.followUp(original, text).also { replyDraft = it }
        DeliveryQueue.enqueue(context, item)
        replySaved.value = original.sessionId
        replyDraft = null
        "Follow-up queued as a new request"
    }

    fun retry(id: String) = perform {
        DeliveryQueue.retry(context, id, acknowledgePossibleDuplicate = true)
        "Retry queued with the original request ID"
    }

    fun delete(id: String) = perform {
        check(database.deliveryDao().deleteHistory(id) == 1) { "Active deliveries cannot be deleted" }
        "History entry deleted"
    }

    fun export(uri: Uri, entries: List<InteractionEntity>) = perform {
        val json = JSONArray()
        entries.forEach { item ->
            json.put(JSONObject().apply {
                put("requestId", item.sessionId); put("conversationId", item.conversationId ?: JSONObject.NULL)
                put("type", item.type); put("text", item.requestText); put("capturedAt", item.timestamp)
                put("timeZone", item.timeZone); put("source", item.source); put("status", item.status)
                put("httpCode", item.httpCode ?: JSONObject.NULL); put("response", item.responseText ?: JSONObject.NULL)
                put("inReplyTo", item.inReplyTo ?: JSONObject.NULL)
                put("contextToken", item.contextToken ?: JSONObject.NULL)
                put("responseMetadata", item.responseMetadata?.let(::JSONObject) ?: JSONObject.NULL)
            })
        }
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open export file" }
            .bufferedWriter().use { it.write(json.toString(2)) }
        "Exported ${entries.size} entries"
    }

    private fun perform(action: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { message.value = withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "History operation failed" }
            finally { busy.value = false }
        }
    }
}
