package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.ReplySenderTags
import com.example.network.DiscoveryManager
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessagingRobolectricTest {

    @Test
    fun `real tcp socket e2ee chat invisible mode and fast discovery`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receivedText = CompletableFuture<String>()
        val receivedReplyTag = CompletableFuture<String?>()
        val receivedReaction = CompletableFuture<String>()
        val peerForegroundState = CompletableFuture<Boolean>()
        val peerVerifiedState = CompletableFuture<Boolean>()

        try {
            val aliceMat = CryptoEngine.createIdentityMaterial()
            val bobMat = CryptoEngine.createIdentityMaterial()

            val testListener = object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String) = false
                override suspend fun onPeerHandshaked(
                    peerId: String,
                    publicKeyBase64: String,
                    remoteIp: String,
                    remoteListenPort: Int,
                    username: String,
                    avatarBase64: String?,
                    viaBridgePeerId: String
                ) {}
                override suspend fun onPeerDisconnected(peerId: String) {}
                override suspend fun onPeerForegroundChanged(peerId: String, inForeground: Boolean) {
                    if (!inForeground) peerForegroundState.complete(false)
                }
                override suspend fun onIncomingTextMessage(
                    peerId: String,
                    messageId: String,
                    text: String,
                    selfDestructSec: Int,
                    createdAt: Long,
                    replyToMessageId: String?,
                    replyToSenderTag: String?,
                    replyToPreview: String?
                ) {
                    receivedReplyTag.complete(replyToSenderTag)
                    receivedText.complete(text)
                }
                override suspend fun onIncomingFileStart(
                    peerId: String,
                    messageId: String,
                    messageType: MessageType,
                    fileName: String,
                    mimeType: String,
                    totalBytes: Long,
                    audioDurationMs: Long,
                    selfDestructSec: Int,
                    createdAt: Long,
                    replyToMessageId: String?,
                    replyToSenderTag: String?,
                    replyToPreview: String?,
                    mediaWidth: Int,
                    mediaHeight: Int
                ): File = File.createTempFile("chatso_test", ".bin")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onIncomingReaction(peerId: String, messageId: String, emoji: String) {
                    receivedReaction.complete("$messageId:$emoji")
                }
                override suspend fun onPeerVerificationChanged(peerId: String, verifiedMe: Boolean) {
                    if (verifiedMe) peerVerifiedState.complete(true)
                }
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }

            val discA = DiscoveryManager(context, scope)
            val discB = DiscoveryManager(context, scope)
            val nodeA = P2PNodeManager(scope, discA, testListener)
            val nodeB = P2PNodeManager(scope, discB, testListener)

            val aliceIdentity = LocalIdentity(
                isInitialized = true,
                username = "Alice",
                publicKeyBase64 = aliceMat.publicKeyBase64,
                wrappedPrivateKeyBase64 = aliceMat.wrappedPrivateKeyBase64,
                peerId = aliceMat.peerId,
                fingerprint = aliceMat.fingerprint,
                listenPort = 43901,
                isDiscoverable = true
            )
            val bobIdentity = LocalIdentity(
                isInitialized = true,
                username = "Bob",
                publicKeyBase64 = bobMat.publicKeyBase64,
                wrappedPrivateKeyBase64 = bobMat.wrappedPrivateKeyBase64,
                peerId = bobMat.peerId,
                fingerprint = bobMat.fingerprint,
                listenPort = 43902,
                isDiscoverable = false
            )

            nodeA.setLocalAppInForeground(true)
            nodeB.setLocalAppInForeground(true)
            nodeA.updateIdentityAndEnsureServer(aliceIdentity)
            nodeB.updateIdentityAndEnsureServer(bobIdentity)
            repeat(40) {
                if (nodeA.serverRunning.value && nodeB.serverRunning.value) return@repeat
                delay(50)
            }
            assertTrue(nodeA.serverRunning.value)
            assertTrue(nodeB.serverRunning.value)

            val bobPort = nodeB.boundPort.value
            val alicePort = nodeA.boundPort.value

            var probeRejected = false
            Socket().use { probeSock ->
                probeSock.connect(InetSocketAddress("127.0.0.1", bobPort), 1000)
                val out = DataOutputStream(probeSock.getOutputStream())
                val inp = DataInputStream(probeSock.getInputStream())
                out.writeByte(DiscoveryManager.KIND_DISCOVERY_PROBE.toInt())
                out.writeInt(0)
                out.flush()
                try {
                    inp.readByte()
                } catch (_: EOFException) {
                    probeRejected = true
                }
            }
            assertTrue("Invisible Mode must silently drop discovery probes", probeRejected)

            val scanStartMs = System.currentTimeMillis()
            discB.scanNetworkNow(knownTargets = listOf("127.0.0.1" to alicePort))
            discA.scanNetworkNow(knownTargets = listOf("127.0.0.1" to bobPort))
            delay(100)
            while (discB.isScanning.value || discA.isScanning.value) {
                delay(50)
                if (System.currentTimeMillis() - scanStartMs > 4500L) break
            }
            val scanElapsedMs = System.currentTimeMillis() - scanStartMs
            assertTrue("Fast scan took too long: ${scanElapsedMs}ms", scanElapsedMs < 4000L)
            assertTrue("Visible peer Alice should be discovered", discB.discoveredPeers.value.containsKey(aliceIdentity.peerId))
            assertFalse("Invisible peer Bob must not be discovered", discA.discoveredPeers.value.containsKey(bobIdentity.peerId))

            val connRes = nodeA.connectToPeer("127.0.0.1", bobPort, bobMat.publicKeyBase64)
            assertTrue(connRes.isSuccess)
            val connectedPeerId = connRes.getOrThrow()

            // Verify 3-state presence sync on background switch
            nodeA.setLocalAppInForeground(false)
            assertFalse(peerForegroundState.get(3, TimeUnit.SECONDS))

            val sent = nodeA.sendTextMessage(
                peerId = connectedPeerId,
                messageId = "msg-001",
                text = "Secret message over P2P socket!",
                selfDestructSec = 30,
                createdAt = System.currentTimeMillis(),
                replyToMessageId = "orig-99",
                replyToAuthorType = "ME",
                replyToPreview = "Previous quote"
            )
            assertTrue(sent)
            assertEquals("Secret message over P2P socket!", receivedText.get(3, TimeUnit.SECONDS))
            assertEquals(ReplySenderTags.PEER, receivedReplyTag.get(3, TimeUnit.SECONDS))

            assertTrue(nodeA.sendReaction(connectedPeerId, "msg-001", "👍"))
            assertEquals("msg-001:👍", receivedReaction.get(3, TimeUnit.SECONDS))

            nodeA.sendVerificationStatus(connectedPeerId, true)
            assertTrue(peerVerifiedState.get(3, TimeUnit.SECONDS))

            nodeA.disconnectAll()
            nodeB.disconnectAll()
            discA.shutdown()
            discB.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `background app does not mark incoming messages as read`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_bg_read_test"
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)

        var foreground = false
        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { foreground }
        )

        try {
            repo.currentlyOpenPeerId = peerId
            val mat = CryptoEngine.createIdentityMaterial()
            repo.onPeerHandshaked(
                peerId = peerId,
                publicKeyBase64 = mat.publicKeyBase64,
                remoteIp = "10.8.0.10",
                remoteListenPort = 42850,
                username = "Alice",
                avatarBase64 = null
            )

            // App is in background -> must remain DELIVERED (not READ) even if currentlyOpenPeerId matches
            repo.onIncomingTextMessage(
                peerId = peerId,
                messageId = "msg_bg_1",
                text = "Ping while app is closed",
                selfDestructSec = 0,
                createdAt = System.currentTimeMillis()
            )
            val savedBg = dao.getMessageById("msg_bg_1")
            assertNotNull(savedBg)
            assertEquals(DeliveryStatus.DELIVERED.name, savedBg!!.status)
            assertNull(savedBg.readAt)

            // Returning to foreground marks conversation read
            foreground = true
            repo.markConversationRead(peerId)
            val savedFg = dao.getMessageById("msg_bg_1")
            assertEquals(DeliveryStatus.READ.name, savedFg!!.status)
            assertNotNull(savedFg.readAt)
        } finally {
            repo.shutdown()
            dao.deleteAllMessagesForPeer(peerId)
            dao.deletePeer(peerId)
            scope.cancel()
        }
    }

    @Test
    fun `single message deletion updates preview and mutual verification requires both peers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_delete_verify_test"
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)

        var foreground = false
        var reactionNotifPayload: String? = null
        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { foreground }
        )
        repo.onIncomingReactionNotification = { _, msgId, sender, emoji, snippet, _ ->
            reactionNotifPayload = "$sender:$emoji:$snippet:$msgId"
        }

        try {
            val mat = CryptoEngine.createIdentityMaterial()
            repo.onPeerHandshaked(
                peerId = peerId,
                publicKeyBase64 = mat.publicKeyBase64,
                remoteIp = "10.8.0.22",
                remoteListenPort = 42850,
                username = "Guritso",
                avatarBase64 = null
            )

            // Mutual verification requires both local and remote verification
            dao.setPeerVerified(peerId, true)
            assertFalse(dao.getPeerById(peerId)!!.isMutuallyVerified)
            repo.onPeerVerificationChanged(peerId, true)
            assertTrue(dao.getPeerById(peerId)!!.isMutuallyVerified)

            // Incoming messages & reaction notification while backgrounded
            val t = System.currentTimeMillis()
            repo.onIncomingTextMessage(peerId, "m1", "First message", 0, t)
            repo.onIncomingTextMessage(peerId, "m2", "Second message", 0, t + 100)
            repo.onIncomingReaction(peerId, "m2", "🔥")
            assertEquals("Guritso:🔥:Second message:m2", reactionNotifPayload)

            // Deleting latest message restores previous message as peer preview
            repo.deleteSingleMessage(peerId, "m2")
            assertNull(dao.getMessageById("m2"))
            assertEquals("First message", dao.getPeerById(peerId)!!.lastMessagePreview)

            // Deleting last remaining message clears preview cleanly
            repo.deleteSingleMessage(peerId, "m1")
            assertNull(dao.getMessageById("m1"))
            assertEquals("", dao.getPeerById(peerId)!!.lastMessagePreview)
        } finally {
            repo.shutdown()
            dao.deleteAllMessagesForPeer(peerId)
            dao.deletePeer(peerId)
            scope.cancel()
        }
    }

    @Test
    fun `transfer cancel and retry redownload plus sticker streaming work over real tcp sockets`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()

        val peerSenderId = "peer_sender_cancel_test"
        val peerReceiverId = "peer_receiver_cancel_test"
        dao.deleteAllMessagesForPeer(peerSenderId)
        dao.deleteAllMessagesForPeer(peerReceiverId)
        dao.deletePeer(peerSenderId)
        dao.deletePeer(peerReceiverId)

        val repoA = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        try {
            // Test incoming cancellation + retry request on repository + P2P protocol
            val mat = CryptoEngine.createIdentityMaterial()
            repoA.onPeerHandshaked(
                peerId = peerReceiverId,
                publicKeyBase64 = mat.publicKeyBase64,
                remoteIp = "127.0.0.1",
                remoteListenPort = 43990,
                username = "Receiver",
                avatarBase64 = null
            )

            // Simulate incoming sticker start -> cancelTransfer -> verifies CANCELLED state and deleted file
            val partialFile = repoA.onIncomingFileStart(
                peerId = peerReceiverId,
                messageId = "sticker_inc_1",
                messageType = MessageType.STICKER,
                fileName = "sticker_1.webp",
                mimeType = "image/webp",
                totalBytes = 2048L,
                audioDurationMs = 0L,
                selfDestructSec = 0,
                createdAt = System.currentTimeMillis()
            )
            partialFile.writeBytes(ByteArray(512) { 1 })
            assertTrue(partialFile.exists())
            assertTrue(repoA.activeTransfers.value.any { it.messageId == "sticker_inc_1" })

            repoA.cancelTransfer("sticker_inc_1")
            assertFalse(partialFile.exists())
            assertFalse(repoA.activeTransfers.value.any { it.messageId == "sticker_inc_1" })
            val cancelledMsg = dao.getMessageById("sticker_inc_1")
            assertNotNull(cancelledMsg)
            assertEquals(DeliveryStatus.CANCELLED.name, cancelledMsg!!.status)
            assertEquals(0, cancelledMsg.transferProgress)
            assertEquals(MessageType.STICKER.name, cancelledMsg.type)

            // Test outgoing sticker stream queues cleanly as SENDING when peer is offline and can be cancelled out of DB
            val fakeWebp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00)
            val sent = repoA.sendStickerStream(
                peerId = peerReceiverId,
                inputStream = ByteArrayInputStream(fakeWebp),
                mimeType = "image/webp",
                fileNameHint = "emoji.webp"
            )
            assertFalse(sent) // offline peer -> queued as SENDING
            val latestOutgoing = dao.getLatestMessageForPeer(peerReceiverId)
            assertNotNull(latestOutgoing)
            assertEquals(MessageType.STICKER.name, latestOutgoing!!.type)
            assertEquals(DeliveryStatus.SENDING.name, latestOutgoing.status)

            // Cancelling an outgoing queued transfer removes the message completely
            repoA.cancelTransfer(latestOutgoing.messageId)
            assertNull(dao.getMessageById(latestOutgoing.messageId))

            // Real TCP loopback test for FILE_CANCEL and FILE_RETRY -> re-stream
            val discA = DiscoveryManager(context, scope)
            val discB = DiscoveryManager(context, scope)
            val retryRequestedFuture = CompletableFuture<String>()
            val completedAfterRetryFuture = CompletableFuture<File>()

            val bobMat = CryptoEngine.createIdentityMaterial()
            val aliceMat = CryptoEngine.createIdentityMaterial()

            lateinit var nodeA: P2PNodeManager
            val listenerA = object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String) = true
                override suspend fun onPeerHandshaked(
                    peerId: String, publicKeyBase64: String, remoteIp: String,
                    remoteListenPort: Int, username: String, avatarBase64: String?,
                    viaBridgePeerId: String
                ) {}
                override suspend fun onPeerDisconnected(peerId: String) {}
                override suspend fun onIncomingTextMessage(
                    peerId: String, messageId: String, text: String, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
                ) {}
                override suspend fun onIncomingFileStart(
                    peerId: String, messageId: String, messageType: MessageType, fileName: String,
                    mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                    mediaWidth: Int, mediaHeight: Int
                ): File = File.createTempFile("retry_in", ".webp")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {
                    completedAfterRetryFuture.complete(file)
                }
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }

            val sourceStickerFile = File.createTempFile("src_sticker", ".webp").apply {
                writeBytes(ByteArray(4096) { 7 })
            }
            lateinit var nodeB: P2PNodeManager
            val listenerB = object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String) = true
                override suspend fun onPeerHandshaked(
                    peerId: String, publicKeyBase64: String, remoteIp: String,
                    remoteListenPort: Int, username: String, avatarBase64: String?,
                    viaBridgePeerId: String
                ) {}
                override suspend fun onPeerDisconnected(peerId: String) {}
                override suspend fun onIncomingTextMessage(
                    peerId: String, messageId: String, text: String, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
                ) {}
                override suspend fun onIncomingFileStart(
                    peerId: String, messageId: String, messageType: MessageType, fileName: String,
                    mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                    mediaWidth: Int, mediaHeight: Int
                ): File = File.createTempFile("unused", ".bin")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onPeerRequestedFileRetry(peerId: String, messageId: String) {
                    retryRequestedFuture.complete(messageId)
                    nodeB.sendMediaOrFile(
                        peerId = peerId,
                        messageId = messageId,
                        messageType = MessageType.STICKER,
                        file = sourceStickerFile,
                        fileName = "src_sticker.webp",
                        mimeType = "image/webp",
                        audioDurationMs = 0L,
                        selfDestructSec = 0,
                        createdAt = System.currentTimeMillis(),
                        onProgress = {}
                    )
                }
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }

            nodeA = P2PNodeManager(scope, discA, listenerA)
            nodeB = P2PNodeManager(scope, discB, listenerB)
            nodeA.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "Alice",
                    publicKeyBase64 = aliceMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = aliceMat.wrappedPrivateKeyBase64,
                    peerId = aliceMat.peerId, fingerprint = aliceMat.fingerprint,
                    listenPort = 43911, isDiscoverable = false
                )
            )
            nodeB.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "Bob",
                    publicKeyBase64 = bobMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = bobMat.wrappedPrivateKeyBase64,
                    peerId = bobMat.peerId, fingerprint = bobMat.fingerprint,
                    listenPort = 43912, isDiscoverable = false
                )
            )
            delay(200)
            val bobPeerIdOnA = nodeA.connectToPeer("127.0.0.1", nodeB.boundPort.value, bobMat.publicKeyBase64).getOrThrow()
            delay(150)

            assertTrue(nodeA.sendFileRetryRequest(bobPeerIdOnA, "retry-msg-42"))
            assertEquals("retry-msg-42", retryRequestedFuture.get(3, TimeUnit.SECONDS))
            val downloaded = completedAfterRetryFuture.get(3, TimeUnit.SECONDS)
            assertEquals(4096L, downloaded.length())

            downloaded.delete()
            sourceStickerFile.delete()
            nodeA.disconnectAll()
            nodeB.disconnectAll()
            discA.shutdown()
            discB.shutdown()
        } finally {
            repoA.shutdown()
            dao.deleteAllMessagesForPeer(peerSenderId)
            dao.deleteAllMessagesForPeer(peerReceiverId)
            dao.deletePeer(peerSenderId)
            dao.deletePeer(peerReceiverId)
            scope.cancel()
        }
    }

    @Test
    fun `favorite stickers persist across message deletion deduplicate by sha256 and send natively`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val mediaStorage = MediaStorageHelper(context)
        val peerId = "peer_fav_sticker_test"
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)
        dao.getAllFavoriteStickersOnce().forEach {
            dao.deleteFavoriteSticker(it.stickerId)
            File(it.localPath).delete()
        }

        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = mediaStorage,
            isAppInForegroundProvider = { true }
        )

        val srcDir = File(context.cacheDir, "test_stickers").apply { if (!exists()) mkdirs() }
        val file1 = File(srcDir, "sticker_a.webp").apply {
            writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 5))
        }
        val file2DuplicateBytes = File(srcDir, "sticker_b_copy.webp").apply {
            writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 5))
        }

        try {
            val mat = CryptoEngine.createIdentityMaterial()
            repo.onPeerHandshaked(
                peerId = peerId,
                publicKeyBase64 = mat.publicKeyBase64,
                remoteIp = "127.0.0.1",
                remoteListenPort = 43995,
                username = "StickerPeer",
                avatarBase64 = null
            )

            val msg1 = MessageEntity(
                messageId = "stk_msg_1",
                peerId = peerId,
                isOutgoing = false,
                type = MessageType.STICKER.name,
                mediaLocalPath = file1.absolutePath,
                fileName = "sticker_a.webp",
                mimeType = "image/webp",
                transferProgress = 100
            )
            val msg2SameContent = MessageEntity(
                messageId = "stk_msg_2",
                peerId = peerId,
                isOutgoing = true,
                type = MessageType.STICKER.name,
                mediaLocalPath = file2DuplicateBytes.absolutePath,
                fileName = "sticker_b_copy.webp",
                mimeType = "image/webp",
                transferProgress = 100
            )
            dao.upsertMessage(msg1)
            dao.upsertMessage(msg2SameContent)

            // Favorite first sticker
            assertFalse(repo.isMessageStickerFavorited(msg1))
            assertEquals(true, repo.toggleFavoriteSticker(msg1))
            assertTrue(repo.isMessageStickerFavorited(msg1))
            // Second message with identical bytes resolves as already favorited via SHA-256
            assertTrue(repo.isMessageStickerFavorited(msg2SameContent))

            val savedList = dao.getAllFavoriteStickersOnce()
            assertEquals(1, savedList.size)
            val savedFav = savedList.first()
            val favFile = File(savedFav.localPath)
            assertTrue(favFile.exists())
            assertTrue(favFile.absolutePath.contains("favorite_stickers"))

            // Delete original chat message -> favorite sticker file must stay intact
            repo.deleteSingleMessage(peerId, "stk_msg_1")
            assertNull(dao.getMessageById("stk_msg_1"))
            assertFalse(file1.exists())
            assertTrue(favFile.exists())

            // Native send duplicates favorite into media/ (offline peer marks FAILED, message still recorded)
            assertFalse(repo.sendFavoriteSticker(peerId, savedFav))
            val sentMsg = dao.getLatestMessageForPeer(peerId)
            assertNotNull(sentMsg)
            assertEquals(MessageType.STICKER.name, sentMsg!!.type)
            assertNotNull(sentMsg.mediaLocalPath)
            assertTrue(sentMsg.mediaLocalPath != savedFav.localPath)
            assertTrue(File(sentMsg.mediaLocalPath!!).exists())

            // Sent sticker should NOT pollute in-app saved Photos grid
            val savedPhotos = mediaStorage.listSavedMediaItems(com.example.data.SavedMediaKind.PHOTOS)
            assertFalse(savedPhotos.any { it.file.absolutePath == sentMsg.mediaLocalPath })

            // Deleting the entire conversation leaves the favorite sticker intact
            repo.deleteConversation(peerId)
            assertTrue(favFile.exists())
            assertEquals(1, dao.getAllFavoriteStickersOnce().size)

            // Toggling favorite on matching bytes removes it cleanly
            assertEquals(false, repo.toggleFavoriteSticker(msg2SameContent))
            assertTrue(dao.getAllFavoriteStickersOnce().isEmpty())
            assertFalse(favFile.exists())
        } finally {
            repo.shutdown()
            dao.deleteAllMessagesForPeer(peerId)
            dao.deletePeer(peerId)
            srcDir.deleteRecursively()
            scope.cancel()
        }
    }

    @Test
    fun `offline queued messages automatically send in order upon peer reconnect`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_offline_queue_test"
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)

        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        val discRemote = DiscoveryManager(context, scope)
        val remoteMat = CryptoEngine.createIdentityMaterial()
        val receivedOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
        val allDeliveredFuture = CompletableFuture<Boolean>()
        val remoteListener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {
                receivedOrder.add(text)
                if (receivedOrder.size >= 2) {
                    allDeliveredFuture.complete(true)
                }
            }
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_q", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val remoteNode = P2PNodeManager(scope, discRemote, remoteListener)

        try {
            val localStore = LocalIdentityStore(context)
            localStore.createIdentity("Sender", null, true)
            repeat(20) {
                if (repo.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            assertTrue(repo.nodeManager.serverRunning.value)

            remoteNode.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "RemotePeer",
                    publicKeyBase64 = remoteMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = remoteMat.wrappedPrivateKeyBase64,
                    peerId = remoteMat.peerId,
                    fingerprint = remoteMat.fingerprint,
                    listenPort = 43931,
                    isDiscoverable = false
                )
            )
            delay(200)

            // Save remote peer in DB as currently offline (unreachable port 1)
            dao.upsertPeer(
                com.example.data.PeerEntity(
                    peerId = remoteMat.peerId,
                    username = "RemotePeer",
                    publicKeyBase64 = remoteMat.publicKeyBase64,
                    fingerprint = remoteMat.fingerprint,
                    lastIp = "127.0.0.1",
                    lastPort = 1
                )
            )

            assertFalse(repo.sendText(remoteMat.peerId, "Queued First"))
            delay(15)
            assertFalse(repo.sendText(remoteMat.peerId, "Queued Second"))

            val queued = dao.getPendingOutgoingMessagesChronological(remoteMat.peerId)
            assertEquals(2, queued.size)
            assertEquals(DeliveryStatus.SENDING.name, queued[0].status)
            assertEquals(DeliveryStatus.SENDING.name, queued[1].status)

            // Now remote peer connects to sender (handshake completes on reconnect)
            val senderId = localStore.getSnapshot()
            assertTrue(
                remoteNode.connectToPeer("127.0.0.1", repo.nodeManager.boundPort.value, senderId.publicKeyBase64).isSuccess
            )

            assertTrue(allDeliveredFuture.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("Queued First", "Queued Second"), receivedOrder.toList())

            // Pending queue is now empty because both messages transitioned out of SENDING
            delay(300)
            assertTrue(dao.getPendingOutgoingMessagesChronological(remoteMat.peerId).isEmpty())
        } finally {
            remoteNode.disconnectAll()
            discRemote.shutdown()
            repo.shutdown()
            dao.deleteAllMessagesForPeer(remoteMat.peerId)
            dao.deletePeer(remoteMat.peerId)
            dao.deleteAllMessagesForPeer(peerId)
            dao.deletePeer(peerId)
            scope.cancel()
        }
    }

    @Test
    fun `open chat while scrolled up keeps messages unread until viewed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        val peerId = "peer_scroll_read_${System.currentTimeMillis()}"

        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = LocalIdentityStore(context),
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        try {
            dao.upsertPeer(
                com.example.data.PeerEntity(
                    peerId = peerId,
                    username = "ScrollPeer",
                    publicKeyBase64 = "K",
                    fingerprint = "F"
                )
            )
            // Chat is open in foreground, but user is scrolled up reading older history
            repo.currentlyOpenPeerId = peerId
            repo.isOpenChatAtBottom = false

            repo.onIncomingTextMessage(
                peerId = peerId,
                messageId = "msg_up_1",
                text = "Scrolled up msg 1",
                selfDestructSec = 0,
                createdAt = 1000L,
                replyToMessageId = null,
                replyToSenderTag = null,
                replyToPreview = null
            )
            repo.onIncomingTextMessage(
                peerId = peerId,
                messageId = "msg_up_2",
                text = "Scrolled up msg 2",
                selfDestructSec = 0,
                createdAt = 2000L,
                replyToMessageId = null,
                replyToSenderTag = null,
                replyToPreview = null
            )

            // Neither message should be marked READ yet, unreadCount == 2, divider anchored at msg_up_1
            assertEquals(DeliveryStatus.DELIVERED.name, dao.getMessageById("msg_up_1")?.status)
            assertEquals(DeliveryStatus.DELIVERED.name, dao.getMessageById("msg_up_2")?.status)
            assertEquals(2, dao.getPeerById(peerId)?.unreadCount)
            assertEquals("msg_up_1", repo.unreadDivider.value?.anchorMessageId)
            assertEquals(2, repo.unreadDivider.value?.unreadCount)

            // User sees msg_up_1 first -> only msg_up_1 transitions to READ, unreadCount becomes 1
            repo.markVisibleMessagesRead(peerId, setOf("msg_up_1"))
            assertEquals(DeliveryStatus.READ.name, dao.getMessageById("msg_up_1")?.status)
            assertEquals(DeliveryStatus.DELIVERED.name, dao.getMessageById("msg_up_2")?.status)
            assertEquals(1, dao.getPeerById(peerId)?.unreadCount)

            // User scrolls to bottom and sees msg_up_2 -> transitions to READ, unreadCount == 0
            repo.isOpenChatAtBottom = true
            repo.markVisibleMessagesRead(peerId, setOf("msg_up_2"))
            assertEquals(DeliveryStatus.READ.name, dao.getMessageById("msg_up_2")?.status)
            assertEquals(0, dao.getPeerById(peerId)?.unreadCount)
        } finally {
            repo.shutdown()
            dao.deleteAllMessagesForPeer(peerId)
            dao.deletePeer(peerId)
            scope.cancel()
        }
    }

    @Test
    fun `deleted conversation closes session and does not reappear on profile sync or auto reconnect`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        dao.getAllPeersOnce().forEach {
            dao.deleteAllMessagesForPeer(it.peerId)
            dao.deletePeer(it.peerId)
        }

        val storeA = LocalIdentityStore(context)
        storeA.createIdentity("AppOne", null, isDiscoverable = true)
        val repoA = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = storeA,
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )

        val discB = DiscoveryManager(context, scope)
        val matB = CryptoEngine.createIdentityMaterial()
        val listenerB = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_del", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val nodeB = P2PNodeManager(scope, discB, listenerB)

        try {
            repeat(30) {
                if (repoA.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            nodeB.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true, username = "AppTwo",
                    publicKeyBase64 = matB.publicKeyBase64, wrappedPrivateKeyBase64 = matB.wrappedPrivateKeyBase64,
                    peerId = matB.peerId, fingerprint = matB.fingerprint,
                    listenPort = 43992, isDiscoverable = true
                )
            )
            repeat(30) {
                if (nodeB.serverRunning.value) return@repeat
                delay(50)
            }
            val aIdentity = storeA.getSnapshot()
            val aPort = repoA.nodeManager.boundPort.value

            // 1. Both peers establish a live session and save the conversation
            assertTrue(
                nodeB.connectToPeer("127.0.0.1", aPort, aIdentity.publicKeyBase64).isSuccess
            )
            delay(200)
            assertNotNull(dao.getPeerById(matB.peerId))
            assertTrue(repoA.nodeManager.connectedPeers.value.contains(matB.peerId))

            // 2. Delete the conversation in AppOne
            repoA.deleteConversation(matB.peerId)
            delay(150)

            assertNull("Peer must be removed from Room DB", dao.getPeerById(matB.peerId))
            assertFalse(
                "Deleting conversation must close the live P2P session",
                repoA.nodeManager.connectedPeers.value.contains(matB.peerId)
            )

            // 3. Switching to AppTwo triggers its auto-connect (isAutoReconnect = true) before AppTwo deletes;
            //    AppOne must reject the auto-reconnect and keep the conversation deleted
            val autoReconnectRes = nodeB.connectToPeer(
                host = "127.0.0.1",
                port = aPort,
                expectedPubKeyBase64 = aIdentity.publicKeyBase64,
                isAutoReconnect = true
            )
            assertTrue("Auto-reconnect to removed chat must be rejected", autoReconnectRes.isFailure)
            delay(150)
            assertNull("Auto-reconnect must not recreate deleted conversation", dao.getPeerById(matB.peerId))

            // 4. Stale PROFILE_SYNC callback must never recreate a deleted peer
            repoA.onPeerProfileUpdated(
                peerId = matB.peerId,
                publicKeyBase64 = matB.publicKeyBase64,
                remoteIp = "127.0.0.1",
                remoteListenPort = nodeB.boundPort.value,
                username = "AppTwo",
                avatarBase64 = null
            )
            assertNull("PROFILE_SYNC must not recreate deleted conversation", dao.getPeerById(matB.peerId))

            // 5. Running AppOne autoConnectActiveChats must not reconnect or recreate anything
            repoA.autoConnectActiveChats(force = true)
            assertTrue(dao.getAllPeersOnce().isEmpty())
            assertFalse(repoA.nodeManager.connectedPeers.value.contains(matB.peerId))

            // 6. Legacy/old app version reconnects passively without the "auto" flag (isAutoReconnect = false):
            //    AppOne must keep the session silent and NOT recreate the deleted chat on handshake/PROFILE_SYNC
            val legacyReconnectRes = nodeB.connectToPeer(
                host = "127.0.0.1",
                port = aPort,
                expectedPubKeyBase64 = aIdentity.publicKeyBase64,
                isAutoReconnect = false
            )
            assertTrue("Legacy passive reconnect should finish silently", legacyReconnectRes.isSuccess)
            delay(250)
            assertNull(
                "Legacy passive reconnect without any real message must NOT recreate deleted chat",
                dao.getPeerById(matB.peerId)
            )
            assertFalse(
                "Silent dismissed session must not appear in connectedPeers UI count",
                repoA.nodeManager.connectedPeers.value.contains(matB.peerId)
            )

            // 7. Only if the remote peer actually sends a real message does the chat materialize
            assertTrue(
                nodeB.sendTextMessage(
                    peerId = aIdentity.peerId,
                    messageId = "real-after-delete-1",
                    text = "Oi de novo",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            delay(250)
            val materializedPeer = dao.getPeerById(matB.peerId)
            assertNotNull("Real incoming message must restore the chat", materializedPeer)
            assertEquals("AppTwo", materializedPeer?.username)
            assertTrue(repoA.nodeManager.connectedPeers.value.contains(matB.peerId))
        } finally {
            nodeB.disconnectAll()
            discB.shutdown()
            repoA.shutdown()
            dao.deleteAllMessagesForPeer(matB.peerId)
            dao.deletePeer(matB.peerId)
            scope.cancel()
        }
    }

    @Test
    fun `instanceId isolates colliding keys, detects installation mismatch, and blocks blank-inst loopback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.getInstance(context)
        val dao = db.chatDao()
        dao.getAllPeersOnce().forEach {
            dao.deleteAllMessagesForPeer(it.peerId)
            dao.deletePeer(it.peerId)
        }

        val store = LocalIdentityStore(context)
        val inst1 = store.ensureInstanceId()
        assertTrue("Generated instanceId must be non-blank", inst1.isNotBlank())
        assertEquals("Subsequent ensureInstanceId calls must persist same token", inst1, store.ensureInstanceId())

        store.createIdentity("PrimaryInstall", null, isDiscoverable = true)
        val repo = ChatRepository(
            context = context,
            scope = scope,
            chatDao = dao,
            identityStore = store,
            mediaStorage = MediaStorageHelper(context),
            isAppInForegroundProvider = { true }
        )
        val discPeer = DiscoveryManager(context, scope)
        val dummyListener = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {}
            override suspend fun onPeerDisconnected(peerId: String) {}
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {}
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?,
                mediaWidth: Int, mediaHeight: Int
            ): File = File.createTempFile("unused_inst", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }
        val remoteNode = P2PNodeManager(scope, discPeer, dummyListener)
        val remoteMat = CryptoEngine.createIdentityMaterial()

        try {
            repeat(30) {
                if (repo.nodeManager.serverRunning.value) return@repeat
                delay(50)
            }
            val primary = store.getSnapshot()
            val port = repo.nodeManager.boundPort.value

            // 1. Key collision check: another install using the SAME keypair/peerId but DIFFERENT instanceId is rejected!
            remoteNode.updateIdentityAndEnsureServer(
                primary.copy(instanceId = "colliding_clone_install_id", listenPort = 0)
            )
            repeat(30) {
                if (remoteNode.serverRunning.value) return@repeat
                delay(50)
            }
            val collisionRes = remoteNode.connectToPeer("127.0.0.1", port, primary.publicKeyBase64)
            assertTrue("Conflicting key with different instanceId must be rejected", collisionRes.isFailure)

            // 2. Normal distinct contact connects with inst_alpha -> persisted on PeerEntity
            remoteNode.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "RemoteAlpha",
                    publicKeyBase64 = remoteMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = remoteMat.wrappedPrivateKeyBase64,
                    peerId = remoteMat.peerId,
                    fingerprint = remoteMat.fingerprint,
                    listenPort = 0,
                    isDiscoverable = false,
                    instanceId = "inst_remote_alpha"
                )
            )
            assertTrue(remoteNode.connectToPeer("127.0.0.1", port, primary.publicKeyBase64).isSuccess)
            delay(150)
            assertEquals("inst_remote_alpha", dao.getPeerById(remoteMat.peerId)?.instanceId)

            // 3. Same keypair switches to a different installation (inst_remote_beta) without QR token -> rejected!
            remoteNode.disconnectAll()
            remoteNode.updateIdentityAndEnsureServer(
                LocalIdentity(
                    isInitialized = true,
                    username = "RemoteBeta",
                    publicKeyBase64 = remoteMat.publicKeyBase64,
                    wrappedPrivateKeyBase64 = remoteMat.wrappedPrivateKeyBase64,
                    peerId = remoteMat.peerId,
                    fingerprint = remoteMat.fingerprint,
                    listenPort = 0,
                    isDiscoverable = false,
                    instanceId = "inst_remote_beta"
                )
            )
            val mismatchRes = remoteNode.connectToPeer("127.0.0.1", port, primary.publicKeyBase64)
            assertTrue("Changed installation ID without QR token must be rejected", mismatchRes.isFailure)

            // With valid QR invite token, the updated installation ID is accepted and updated cleanly
            val qrAuthorizedRes = remoteNode.connectToPeer(
                host = "127.0.0.1",
                port = port,
                expectedPubKeyBase64 = primary.publicKeyBase64,
                inviteToken = primary.qrInviteToken
            )
            assertTrue("QR invite token should authorize updated installation ID", qrAuthorizedRes.isSuccess)
            delay(150)
            assertEquals("inst_remote_beta", dao.getPeerById(remoteMat.peerId)?.instanceId)

            // 4. Unsolicited legacy APK with blank instanceId connecting over local device IP (127.0.0.1) is rejected
            val legacyMat = CryptoEngine.createIdentityMaterial()
            val legacyPriv = CryptoEngine.unwrapPrivateKey(legacyMat.wrappedPrivateKeyBase64)
            val eph = CryptoEngine.generateIdentityKeyPair()
            val ephB64 = android.util.Base64.encodeToString(eph.public.encoded, android.util.Base64.NO_WRAP)
            val nonceB64 = android.util.Base64.encodeToString(CryptoEngine.randomBytes(16), android.util.Base64.NO_WRAP)
            val sigB64 = CryptoEngine.signHandshake(
                identityPrivateKey = legacyPriv,
                roleTag = "CLIENT",
                identityPubBase64 = legacyMat.publicKeyBase64,
                ephemeralPubBase64 = ephB64,
                nonceBase64 = nonceB64
            )
            val legacyHello = org.json.JSONObject().apply {
                put("pub", legacyMat.publicKeyBase64)
                put("eph", ephB64)
                put("nonce", nonceB64)
                put("sig", sigB64)
                put("port", 43999)
                put("token", "")
                put("auto", false)
                // intentionally omit "inst" to simulate pre-instanceId APK
            }.toString().toByteArray(Charsets.UTF_8)

            Socket("127.0.0.1", port).use { sock ->
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                out.writeByte(0x03) // KIND_HANDSHAKE_HELLO
                out.writeInt(legacyHello.size)
                out.write(legacyHello)
                out.flush()
                val respKind = inp.readByte()
                assertEquals(0x05.toByte(), respKind) // KIND_HANDSHAKE_REJECT
            }
            assertNull(dao.getPeerById(legacyMat.peerId))
        } finally {
            remoteNode.disconnectAll()
            discPeer.shutdown()
            repo.shutdown()
            dao.deleteAllMessagesForPeer(remoteMat.peerId)
            dao.deletePeer(remoteMat.peerId)
            scope.cancel()
        }
    }
}
