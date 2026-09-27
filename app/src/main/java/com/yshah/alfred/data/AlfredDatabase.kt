package com.yshah.alfred.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [InteractionEntity::class, DeliveryEntity::class], version = 2, exportSchema = false)
abstract class AlfredDatabase : RoomDatabase() {
    abstract fun interactionDao(): InteractionDao
    abstract fun deliveryDao(): DeliveryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE interactions ADD COLUMN conversationId TEXT")
                db.execSQL("ALTER TABLE interactions ADD COLUMN httpCode INTEGER")
                db.execSQL("ALTER TABLE interactions ADD COLUMN timeZone TEXT NOT NULL DEFAULT 'UTC'")
                db.execSQL("ALTER TABLE interactions ADD COLUMN source TEXT NOT NULL DEFAULT 'phone'")
                db.execSQL("CREATE TABLE IF NOT EXISTS deliveries (requestId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, type TEXT NOT NULL, capturedAt INTEGER NOT NULL, timeZone TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, message TEXT, httpCode INTEGER, resultPublished INTEGER NOT NULL, resultRevision INTEGER NOT NULL)")
            }
        }
    }
}
