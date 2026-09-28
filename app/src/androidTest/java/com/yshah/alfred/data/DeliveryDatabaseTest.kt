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
            .addMigrations(AlfredDatabase.MIGRATION_1_2, AlfredDatabase.MIGRATION_2_3).build()
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

    @Test fun releasedV2PreservesOutboxAndRoundTripsFollowUpMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE interactions (sessionId TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, requestText TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, responseText TEXT, conversationId TEXT, httpCode INTEGER, timeZone TEXT NOT NULL DEFAULT 'UTC', source TEXT NOT NULL DEFAULT 'phone')")
            old.execSQL("CREATE TABLE deliveries (requestId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, type TEXT NOT NULL, capturedAt INTEGER NOT NULL, timeZone TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, message TEXT, httpCode INTEGER, resultPublished INTEGER NOT NULL, resultRevision INTEGER NOT NULL)")
            old.execSQL("INSERT INTO interactions VALUES ('old', 'task', 'keep me', 1234, 'accepted', 'saved', 'thread', 202, 'UTC', 'phone')")
            old.execSQL("INSERT INTO deliveries VALUES ('old', 'keep me', 'task', 1234, 'UTC', 'phone', 'pending', NULL, NULL, 0, 1)")
            old.version = 2
        }
        fun open() = Room.databaseBuilder(context, AlfredDatabase::class.java, name)
            .addMigrations(AlfredDatabase.MIGRATION_1_2, AlfredDatabase.MIGRATION_2_3).build()
        val metadata = """{"receipt":{"action":"updated","externalId":"1","url":"https://example.com/1"},"clarification":{"token":"next","question":"Anything else?"},"conversationId":"thread"}"""
        val reply = DeliveryEntity("reply", "Move it", "task", 2345, "UTC", "phone",
            conversationId = "thread", inReplyTo = "old", contextToken = "opaque")
        var db = open()
        try {
            assertEquals("pending", db.deliveryDao().get("old")!!.status)
            assertNull(db.deliveryDao().get("old")!!.responseMetadata)
            db.openHelper.writableDatabase.query("SELECT requestText, conversationId, httpCode, inReplyTo FROM interactions WHERE sessionId = 'old'").use {
                assertTrue(it.moveToFirst()); assertEquals("keep me", it.getString(0))
                assertEquals("thread", it.getString(1)); assertEquals(202, it.getInt(2)); assertTrue(it.isNull(3))
            }
            db.deliveryDao().insert(reply)
            db.deliveryDao().finish("reply", "needs_confirmation", "Anything else?", 200, metadata)
            val finished = db.deliveryDao().get("reply")!!
            com.yshah.alfred.webhook.DeliveryQueue.record(db, finished)
            db.close()
            db = open()
            assertEquals(reply.copy(status = "needs_confirmation", message = "Anything else?", httpCode = 200, responseMetadata = metadata, resultRevision = 2), db.deliveryDao().get("reply"))
            db.openHelper.writableDatabase.query("SELECT inReplyTo, contextToken, responseMetadata FROM interactions WHERE sessionId = 'reply'").use {
                assertTrue(it.moveToFirst()); assertEquals("old", it.getString(0))
                assertEquals("opaque", it.getString(1)); assertEquals(metadata, it.getString(2))
            }
            db.deliveryDao().finish("reply", "failed", "Try again", 200, metadata)
            assertEquals(1, db.deliveryDao().retry("reply"))
            val retry = db.deliveryDao().get("reply")!!
            assertNull(retry.responseMetadata)
            assertEquals("old", retry.inReplyTo)
            assertEquals("opaque", retry.contextToken)
            assertEquals("thread", retry.conversationId)
            com.yshah.alfred.webhook.DeliveryQueue.record(db, retry)
            db.openHelper.writableDatabase.query("SELECT responseMetadata FROM interactions WHERE sessionId = 'reply'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
