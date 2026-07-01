package com.yshah.aide.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InteractionEntity)

    @Query("SELECT * FROM interactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<InteractionEntity>>
}
