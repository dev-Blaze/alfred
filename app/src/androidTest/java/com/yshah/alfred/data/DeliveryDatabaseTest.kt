package com.yshah.alfred.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryDatabaseTest {
    @Test fun migrationPreservesHistoryAndValidatesRoomSchema() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE interactions (sessionId TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, requestText TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, responseText TEXT)")
            old.execSQL("INSERT INTO interactions VALUES ('old', 'note', 'keep me', 1, 'success', NULL)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, AlfredDatabase::class.java, name)
            .addMigrations(AlfredDatabase.MIGRATION_1_2).build()
        try {
            db.openHelper.writableDatabase.query("SELECT requestText, timeZone, source FROM interactions WHERE sessionId = 'old'").use {
                assertTrue(it.moveToFirst()); assertEquals("keep me", it.getString(0))
                assertEquals("UTC", it.getString(1)); assertEquals("phone", it.getString(2))
            }
            val dao = db.deliveryDao()
            val capture = DeliveryEntity("one", "Buy milk", "task", 1234, "Asia/Kolkata", "watch")
            assertTrue(dao.insert(capture) != -1L)
            assertEquals(-1L, dao.insert(capture.copy(text = "Different")))
            assertEquals(capture, dao.get("one"))
            assertEquals(1, dao.claim("one")); assertEquals(0, dao.claim("one"))
            assertEquals(0, dao.retry("one")) // An active mutation must never replay.
            assertEquals("sending", dao.recoverable().single().status)
            dao.finish("one", "uncertain", "Check destination", null)
            assertEquals(1, dao.retry("one"))
            assertEquals(capture.copy(resultRevision = 3), dao.get("one")) // Retry preserves payload, advances ordering.
            dao.published("one", 1) // A stale publication cannot mark the new attempt published.
            assertFalse(dao.get("one")!!.resultPublished)
            dao.finish("one", "accepted", "Server accepted", 202)
            assertEquals(4L, dao.get("one")!!.resultRevision)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
