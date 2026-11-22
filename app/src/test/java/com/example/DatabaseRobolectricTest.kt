package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatDatabase
import com.example.data.MessageEntity
import com.example.data.MessageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseRobolectricTest {

    @Test
    fun `chat messages query returns newest first with limit pagination`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_pagination_test"
        dao.deleteAllMessagesForPeer(peerId)

        val baseTime = 1_700_000_000_000L
        for (i in 1..220) {
            dao.upsertMessage(
                MessageEntity(
                    messageId = "m_$i",
                    peerId = peerId,
                    isOutgoing = i % 2 == 0,
                    type = MessageType.TEXT.name,
                    textContent = "Msg $i",
                    createdAt = baseTime + i * 1000L
                )
            )
        }

        val page1 = dao.observeMessagesForPeer(peerId, limit = 150).first()
        assertEquals(150, page1.size)
        assertEquals("m_220", page1.first().messageId)
        assertEquals("m_71", page1.last().messageId)

        val page2 = dao.observeMessagesForPeer(peerId, limit = 300).first()
        assertEquals(220, page2.size)
        assertEquals("m_1", page2.last().messageId)

        dao.deleteAllMessagesForPeer(peerId)
    }

    @Test
    fun `migration 3 to 4 creates favorite_stickers table and unique hash index`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_3_4_test.db"
        context.deleteDatabase(dbName)
        val sqliteHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS peers (peerId TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS messages (messageId TEXT NOT NULL PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writable = sqliteHelper.writableDatabase
        ChatDatabase.MIGRATION_3_4.migrate(writable)
        writable.query("SELECT name FROM sqlite_master WHERE type='table' AND name='favorite_stickers'").use { c ->
            assertTrue(c.moveToFirst())
        }
        writable.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_favorite_stickers_sha256'").use { c ->
            assertTrue(c.moveToFirst())
        }
        sqliteHelper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 4 to 5 adds viaBridgePeerId column to peers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_4_5_test.db"
        context.deleteDatabase(dbName)
        val sqliteHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS peers (peerId TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL DEFAULT '')")
                        db.execSQL("INSERT INTO peers (peerId, username) VALUES ('p1', 'Alice')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writable = sqliteHelper.writableDatabase
        ChatDatabase.MIGRATION_4_5.migrate(writable)
        writable.query("SELECT viaBridgePeerId FROM peers WHERE peerId = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
        sqliteHelper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 5 to 6 adds mediaWidth and mediaHeight columns to messages`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_5_6_test.db"
        context.deleteDatabase(dbName)
        val sqliteHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS messages (messageId TEXT NOT NULL PRIMARY KEY, textContent TEXT NOT NULL DEFAULT '')")
                        db.execSQL("INSERT INTO messages (messageId, textContent) VALUES ('m1', 'hello')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writable = sqliteHelper.writableDatabase
        ChatDatabase.MIGRATION_5_6.migrate(writable)
        writable.query("SELECT mediaWidth, mediaHeight FROM messages WHERE messageId = 'm1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
        }
        sqliteHelper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 6 to 7 adds instanceId column to peers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_6_7_test.db"
        context.deleteDatabase(dbName)
        val sqliteHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS peers (peerId TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL DEFAULT '')")
                        db.execSQL("INSERT INTO peers (peerId, username) VALUES ('p1', 'Alice')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val writable = sqliteHelper.writableDatabase
        ChatDatabase.MIGRATION_6_7.migrate(writable)
        writable.query("SELECT instanceId FROM peers WHERE peerId = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
        sqliteHelper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `monotonic status queries never downgrade DELIVERED or READ back to SENT`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_monotonic_status_test"
        dao.deleteAllMessagesForPeer(peerId)

        dao.upsertMessage(
            MessageEntity(
                messageId = "mono_1",
                peerId = peerId,
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = "fast ack race",
                status = com.example.data.DeliveryStatus.SENDING.name
            )
        )
        // Simulate DELIVERED ACK arriving before sendText post-write runs SENT
        dao.markMessageDeliveredIfNotRead("mono_1")
        assertEquals(com.example.data.DeliveryStatus.DELIVERED.name, dao.getMessageById("mono_1")?.status)

        dao.markMessageSentIfPending("mono_1")
        assertEquals(com.example.data.DeliveryStatus.DELIVERED.name, dao.getMessageById("mono_1")?.status)

        dao.markTransferCompleteIfPending("mono_1", 100, com.example.data.DeliveryStatus.SENT.name)
        assertEquals(com.example.data.DeliveryStatus.DELIVERED.name, dao.getMessageById("mono_1")?.status)

        // Simulate READ arriving, then late DELIVERED or SENT
        dao.markMessageRead("mono_1", com.example.data.DeliveryStatus.READ.name, 123456L, null)
        dao.markMessageDeliveredIfNotRead("mono_1")
        dao.markMessageSentIfPending("mono_1")
        assertEquals(com.example.data.DeliveryStatus.READ.name, dao.getMessageById("mono_1")?.status)
        assertEquals(123456L, dao.getMessageById("mono_1")?.readAt)

        dao.deleteAllMessagesForPeer(peerId)
    }
}
