package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val username: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val avatarPath: String? = null,
    val lastIp: String = "",
    val lastPort: Int = LocalIdentity.DEFAULT_TCP_PORT,
    val isVerified: Boolean = false,
    val keyChangedWarning: Boolean = false,
    val selfDestructSeconds: Int = 0,
    val lastMessagePreview: String = "",
    val lastActivityAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["peerId"]), Index(value = ["expiresAt"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val peerId: String,
    val isOutgoing: Boolean,
    val type: String, // MessageType.name
    val textContent: String = "",
    val mediaLocalPath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val audioDurationMs: Long = 0L,
    val transferProgress: Int = 100,
    val status: String = DeliveryStatus.SENT.name,
    val selfDestructSeconds: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
    val expiresAt: Long? = null
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM peers ORDER BY lastActivityAt DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    fun observePeer(peerId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeerById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersOnce(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET unreadCount = 0 WHERE peerId = :peerId")
    suspend fun clearUnread(peerId: String)

    @Query("UPDATE peers SET isVerified = :verified, keyChangedWarning = 0 WHERE peerId = :peerId")
    suspend fun setPeerVerified(peerId: String, verified: Boolean)

    @Query("UPDATE peers SET selfDestructSeconds = :seconds WHERE peerId = :peerId")
    suspend fun updatePeerSelfDestruct(peerId: String, seconds: Int)

    @Query("DELETE FROM peers WHERE peerId = :peerId")
    suspend fun deletePeer(peerId: String)

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAt ASC")
    fun observeMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET transferProgress = :progress, status = :status, mediaLocalPath = COALESCE(:localPath, mediaLocalPath) WHERE messageId = :messageId")
    suspend fun updateTransferProgress(
        messageId: String,
        progress: Int,
        status: String,
        localPath: String? = null
    )

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status, readAt = :readAt, expiresAt = :expiresAt WHERE messageId = :messageId")
    suspend fun markMessageRead(
        messageId: String,
        status: String,
        readAt: Long,
        expiresAt: Long?
    )

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isOutgoing = 0 AND status != 'READ'")
    suspend fun getUnreadIncomingMessages(peerId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun getExpiredMessages(now: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAllMessagesForPeer(peerId: String)
}

@Database(
    entities = [PeerEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chatso_local.db"
                ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
            }
        }
    }
}
