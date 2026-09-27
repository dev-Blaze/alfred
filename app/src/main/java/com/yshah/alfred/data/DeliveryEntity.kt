package com.yshah.alfred.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "deliveries")
data class DeliveryEntity(
    @PrimaryKey val requestId: String,
    val text: String,
    val type: String,
    val capturedAt: Long,
    val timeZone: String,
    val source: String,
    val status: String = "pending",
    val message: String? = null,
    val httpCode: Int? = null,
    val resultPublished: Boolean = false,
    val resultRevision: Long = 1,
)

@Dao
interface DeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delivery: DeliveryEntity): Long

    @Query("SELECT * FROM deliveries WHERE requestId = :id")
    suspend fun get(id: String): DeliveryEntity?

    @Query("SELECT * FROM deliveries WHERE status IN ('pending', 'sending') OR (source = 'watch' AND resultPublished = 0)")
    suspend fun recoverable(): List<DeliveryEntity>

    @Query("UPDATE deliveries SET status = 'sending' WHERE requestId = :id AND status = 'pending'")
    suspend fun claim(id: String): Int

    @Query("UPDATE deliveries SET status = :status, message = :message, httpCode = :code, resultPublished = 0, resultRevision = resultRevision + 1 WHERE requestId = :id")
    suspend fun finish(id: String, status: String, message: String?, code: Int?)

    @Query("UPDATE deliveries SET resultPublished = 1 WHERE requestId = :id AND resultRevision = :revision")
    suspend fun published(id: String, revision: Long)

    @Query("UPDATE deliveries SET resultPublished = 0 WHERE requestId = :id")
    suspend fun republish(id: String)

    @Query("UPDATE deliveries SET status = 'pending', message = NULL, httpCode = NULL, resultPublished = 0, resultRevision = resultRevision + 1 WHERE requestId = :id AND status IN ('uncertain', 'http_error', 'unknown', 'failed')")
    suspend fun retry(id: String): Int

    @Query("DELETE FROM interactions WHERE sessionId = :id AND NOT EXISTS (SELECT 1 FROM deliveries WHERE requestId = :id AND status IN ('pending', 'sending'))")
    suspend fun deleteHistory(id: String): Int

    @Query("SELECT requestId FROM deliveries WHERE status IN ('uncertain', 'http_error', 'unknown', 'failed')")
    fun observeRetryableIds(): kotlinx.coroutines.flow.Flow<List<String>>
}
