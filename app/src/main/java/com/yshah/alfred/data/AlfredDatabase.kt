package com.yshah.alfred.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [InteractionEntity::class], version = 1, exportSchema = false)
abstract class AlfredDatabase : RoomDatabase() {
    abstract fun interactionDao(): InteractionDao
}
