package com.yshah.alfred.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.yshah.alfred.webhook.deliveryStatusLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yshah.alfred.data.InteractionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler
import com.yshah.alfred.network.responseMetadata
import com.yshah.alfred.network.safeReceiptUrl

private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val interactions by viewModel.interactions.collectAsState()
    val retryable by viewModel.retryableIds.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var retryId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
    val replySaved by viewModel.replySaved.collectAsState()
    LaunchedEffect(replySaved) {
        if (replySaved != null && replySaved == replyId) { replyId = null; replyText = "" }
    }
    val filtered = interactions.filter { item ->
        listOf(item.requestText, item.responseText.orEmpty(), item.status, item.sessionId,
            item.conversationId.orEmpty()).any { it.contains(query, ignoreCase = true) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it, filtered) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("History") }, actions = {
        TextButton(enabled = !busy && filtered.isNotEmpty(), onClick = { export.launch("alfred-history.json") }) { Text("Export") }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search latest 500 entries") },
                modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            if (filtered.isEmpty()) Text("No matching interactions", Modifier.padding(16.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(filtered, key = { it.sessionId }) { item ->
                    InteractionRow(item, canRetry = item.sessionId in retryable, busy = busy,
                        onRetry = { retryId = item.sessionId }, onDelete = { deleteId = item.sessionId },
                        onReply = { viewModel.replySaved.value = null; replyId = item.sessionId; replyText = "" })
                    HorizontalDivider()
                }
            }
        }
    }
    retryId?.let { id ->
        AlertDialog(onDismissRequest = { retryId = null }, title = { Text("Retry this request?") },
            text = { Text("The server may already have executed this request. Retrying can create duplicate tasks or notes. Check the destination first. The original request ID is reused, but the server may not deduplicate it.") },
            confirmButton = { TextButton(onClick = { retryId = null; viewModel.retry(id) }, enabled = !busy) { Text("Retry anyway") } },
            dismissButton = { TextButton(onClick = { retryId = null }) { Text("Cancel") } })
    }
    interactions.firstOrNull { it.sessionId == replyId }?.let { original ->
        val question = responseMetadata(original.responseMetadata).clarification?.question
        AlertDialog(onDismissRequest = { if (!busy) replyId = null },
            title = { Text(if (question != null) "Reply" else "Correct request") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question ?: original.requestText)
                Text("Sends a new request linked to this entry.")
                OutlinedTextField(value = replyText, onValueChange = { replyText = it },
                    enabled = !busy, label = { Text("Reply or correction") },
                    isError = replyText.length > 50_000,
                    supportingText = { if (replyText.length > 50_000) Text("Use at most 50,000 characters") },
                    modifier = Modifier.fillMaxWidth())
                message?.let { Text(it) }
            } },
            confirmButton = { TextButton(enabled = !busy && replyText.isNotBlank() && replyText.length <= 50_000,
                onClick = {
                    viewModel.reply(original, replyText)
                }) { Text(if (busy) "Saving…" else "Send") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { replyId = null }) { Text("Cancel") } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete history entry?") },
            text = { Text("This removes the entry from history. It does not undo server actions. The delivery record is retained to prevent duplicate sends.") },
            confirmButton = { TextButton(onClick = { deleteId = null; viewModel.delete(id) }, enabled = !busy) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
    }
}

@Composable
private fun InteractionRow(item: InteractionEntity, canRetry: Boolean, busy: Boolean, onRetry: () -> Unit, onDelete: () -> Unit, onReply: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val metadata = remember(item.responseMetadata) { responseMetadata(item.responseMetadata) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = formatTimestamp(item.timestamp), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(deliveryStatusLabel(item.status), style = MaterialTheme.typography.labelMedium)
        Text(
            text = item.requestText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Response", style = MaterialTheme.typography.labelMedium)
            Text(
                text = item.responseText?.ifBlank { null } ?: statusFallback(item.status),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("${item.source} · ${item.timeZone}" + (item.httpCode?.let { " · HTTP $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
            Text("Request: ${item.sessionId}", style = MaterialTheme.typography.labelSmall)
            (metadata.conversationId ?: item.conversationId)?.let { Text("Conversation: $it", style = MaterialTheme.typography.labelSmall) }
            item.inReplyTo?.let { Text("In reply to: $it", style = MaterialTheme.typography.labelSmall) }
            metadata.clarification?.let { Text(it.question, style = MaterialTheme.typography.bodyMedium) }
            metadata.receipt?.let { receipt ->
                Text(receipt.action, style = MaterialTheme.typography.titleSmall)
                receipt.externalId?.let { Text("External ID: $it", style = MaterialTheme.typography.bodySmall) }
                safeReceiptUrl(receipt.url)?.let { url ->
                    TextButton(onClick = {
                        try { uriHandler.openUri(url); linkError = false }
                        catch (_: Exception) { linkError = true }
                    }) { Text("Open receipt · ${java.net.URI(url).host}") }
                }
                if (linkError) Text("No app could open this link", color = MaterialTheme.colorScheme.error)
            }
            if (item.type in listOf("task", "note") && item.status !in listOf("pending", "sending")) {
                TextButton(enabled = !busy, onClick = onReply) { Text(if (metadata.clarification != null) "Reply" else "Correct") }
            }
            Row {
                if (canRetry) TextButton(enabled = !busy, onClick = onRetry) { Text("Retry") }
                TextButton(enabled = !busy && item.status !in listOf("pending", "sending"), onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun statusFallback(status: String): String = deliveryStatusLabel(status)

private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER)
