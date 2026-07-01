package com.yshah.aide.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [InteractionEntity::class], version = 1, exportSchema = false)
abstract class AideDatabase : RoomDatabase() {
    abstract fun interactionDao(): InteractionDao
}
