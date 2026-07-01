package com.yshah.aide.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "interactions")
data class InteractionEntity(
    @PrimaryKey val sessionId: String,
    val type: String, // "task" | "note" | "image" | "convo"
    val requestText: String,
    val timestamp: Long,
    val status: String, // "success" | "http_error" | "timeout" | "network_error"
    val responseText: String?,
)
