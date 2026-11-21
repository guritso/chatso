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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val peerVerifiedMe: Boolean = false,
    val keyChangedWarning: Boolean = false,
    val selfDestructSeconds: Int = 0,
    val lastMessagePreview: String = "",
    val lastActivityAt: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val viaBridgePeerId: String = ""
) {
    val isMutuallyVerified: Boolean
        get() = isVerified && peerVerifiedMe
}

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
    val expiresAt: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderTag: String? = null,
    val replyToPreview: String? = null,
    val reactionsJson: String = ""
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

    @Query("UPDATE peers SET unreadCount = :count WHERE peerId = :peerId")
    suspend fun setUnreadCount(peerId: String, count: Int)

    @Query("UPDATE peers SET isVerified = :verified, keyChangedWarning = 0 WHERE peerId = :peerId")
    suspend fun setPeerVerified(peerId: String, verified: Boolean)

    @Query("UPDATE peers SET peerVerifiedMe = :verified WHERE peerId = :peerId")
    suspend fun setPeerVerifiedMe(peerId: String, verified: Boolean)

    @Query("UPDATE peers SET keyChangedWarning = :warning WHERE peerId = :peerId")
    suspend fun setPeerKeyChangedWarning(peerId: String, warning: Boolean)

    @Query("UPDATE peers SET lastMessagePreview = :preview, lastActivityAt = :lastActivityAt WHERE peerId = :peerId")
    suspend fun updatePeerLastPreview(peerId: String, preview: String, lastActivityAt: Long)

    @Query("UPDATE peers SET selfDestructSeconds = :seconds WHERE peerId = :peerId")
    suspend fun updatePeerSelfDestruct(peerId: String, seconds: Int)

    @Query("DELETE FROM peers WHERE peerId = :peerId")
    suspend fun deletePeer(peerId: String)

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAt DESC, messageId DESC LIMIT :limit")
    fun observeMessagesForPeer(peerId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeTotalMessageCount(): Flow<Int>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAt DESC, messageId DESC LIMIT 1")
    suspend fun getLatestMessageForPeer(peerId: String): MessageEntity?

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

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactionsJson: String)

    @Query("UPDATE messages SET status = :status, readAt = :readAt, expiresAt = :expiresAt WHERE messageId = :messageId")
    suspend fun markMessageRead(
        messageId: String,
        status: String,
        readAt: Long,
        expiresAt: Long?
    )

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isOutgoing = 0 AND status != 'READ'")
    suspend fun getUnreadIncomingMessages(peerId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isOutgoing = 0 AND status != 'READ' ORDER BY createdAt ASC, messageId ASC")
    suspend fun getUnreadIncomingMessagesChronological(peerId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isOutgoing = 1 AND status IN ('SENDING', 'FAILED') ORDER BY createdAt ASC, messageId ASC")
    suspend fun getPendingOutgoingMessagesChronological(peerId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun getExpiredMessages(now: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAllMessagesForPeer(peerId: String)

    @Query("SELECT * FROM favorite_stickers ORDER BY createdAt DESC")
    fun observeFavoriteStickers(): Flow<List<FavoriteStickerEntity>>

    @Query("SELECT * FROM favorite_stickers")
    suspend fun getAllFavoriteStickersOnce(): List<FavoriteStickerEntity>

    @Query("SELECT * FROM favorite_stickers WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getFavoriteStickerByHash(sha256: String): FavoriteStickerEntity?

    @Query("SELECT * FROM favorite_stickers WHERE stickerId = :stickerId LIMIT 1")
    suspend fun getFavoriteStickerById(stickerId: String): FavoriteStickerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavoriteSticker(sticker: FavoriteStickerEntity)

    @Query("DELETE FROM favorite_stickers WHERE stickerId = :stickerId")
    suspend fun deleteFavoriteSticker(stickerId: String)
}

@Entity(
    tableName = "favorite_stickers",
    indices = [Index(value = ["sha256"], unique = true)]
)
data class FavoriteStickerEntity(
    @PrimaryKey val stickerId: String,
    val localPath: String,
    val mimeType: String,
    val sha256: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Database(
    entities = [PeerEntity::class, MessageEntity::class, FavoriteStickerEntity::class],
    version = 5,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToSenderTag TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToPreview TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN peerVerifiedMe INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_stickers` (
                        `stickerId` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`stickerId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorite_stickers_sha256` ON `favorite_stickers` (`sha256`)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN viaBridgePeerId TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chatso_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
