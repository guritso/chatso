package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.AppLanguage
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.LocalIdentityStore
import com.example.data.LocaleHelper
import com.example.data.MediaStorageHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.ReactionCodec
import com.example.data.ReplySenderTags
import com.example.data.ThemePalette
import com.example.ui.theme.AppIconManager
import com.example.ui.theme.buildChatsoColorScheme
import com.example.ui.theme.spec
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.example.network.ChatsoConnectionService
import com.example.network.ChatsoNotificationActionReceiver
import com.example.network.ChatsoNotificationHelper
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import com.example.network.QrCodeHelper
import java.io.ByteArrayInputStream
import com.example.network.UpdateCheckResult
import com.example.network.UpdateChecker
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
import org.junit.Assert.assertArrayEquals
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
class ExampleRobolectricTest {

    @Test
    fun `app name matches chatso`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("chatso", context.getString(R.string.app_name))
    }

    @Test
    fun `locale helper switches languages and resolves localized strings`() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        LocaleHelper.saveLanguage(base, AppLanguage.EN)
        val enCtx = LocaleHelper.wrapContext(base)
        assertEquals("On Network", enCtx.getString(R.string.connect_lan_tab))
        assertEquals("Visible", enCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.ES)
        val esCtx = LocaleHelper.wrapContext(base)
        assertEquals("En Red", esCtx.getString(R.string.connect_lan_tab))
        assertEquals("Invisible", esCtx.getString(R.string.status_invisible))

        LocaleHelper.saveLanguage(base, AppLanguage.FR)
        val frCtx = LocaleHelper.wrapContext(base)
        assertEquals("Sur Réseau", frCtx.getString(R.string.connect_lan_tab))

        LocaleHelper.saveLanguage(base, AppLanguage.DE)
        val deCtx = LocaleHelper.wrapContext(base)
        assertEquals("Im Netz", deCtx.getString(R.string.connect_lan_tab))
        assertEquals("Sichtbar", deCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.PT)
        val ptCtx = LocaleHelper.wrapContext(base)
        assertEquals("Na Rede", ptCtx.getString(R.string.connect_lan_tab))
        assertEquals("Visível", ptCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.SYSTEM)
    }

    @Test
    fun `ecdsa handshake and ecdh aes256gcm session cipher roundtrip`() {
        val aliceId = CryptoEngine.createIdentityMaterial()
        val bobId = CryptoEngine.createIdentityMaterial()

        assertEquals(
            CryptoEngine.computeSafetyNumber(aliceId.publicKeyBase64, bobId.publicKeyBase64),
            CryptoEngine.computeSafetyNumber(bobId.publicKeyBase64, aliceId.publicKeyBase64)
        )

        val aliceEph = CryptoEngine.generateIdentityKeyPair()
        val bobEph = CryptoEngine.generateIdentityKeyPair()
        val aliceEphB64 = Base64.encodeToString(aliceEph.public.encoded, Base64.NO_WRAP)
        val bobEphB64 = Base64.encodeToString(bobEph.public.encoded, Base64.NO_WRAP)
        val cNonce = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
        val sNonce = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)

        val alicePriv = CryptoEngine.unwrapPrivateKey(aliceId.wrappedPrivateKeyBase64)
        val sig = CryptoEngine.signHandshake(
            alicePriv,
            "CLIENT",
            aliceId.publicKeyBase64,
            aliceEphB64,
            cNonce
        )
        assertTrue(
            CryptoEngine.verifyHandshake(
                "CLIENT",
                aliceId.publicKeyBase64,
                aliceEphB64,
                cNonce,
                sig
            )
        )

        val aliceCipher = CryptoEngine.deriveSessionCipher(
            isInitiator = true,
            localEphemeralPrivate = aliceEph.private,
            remoteEphemeralPubBase64 = bobEphB64,
            clientIdentityPubBase64 = aliceId.publicKeyBase64,
            clientEphPubBase64 = aliceEphB64,
            clientNonceBase64 = cNonce,
            serverIdentityPubBase64 = bobId.publicKeyBase64,
            serverEphPubBase64 = bobEphB64,
            serverNonceBase64 = sNonce
        )
        val bobCipher = CryptoEngine.deriveSessionCipher(
            isInitiator = false,
            localEphemeralPrivate = bobEph.private,
            remoteEphemeralPubBase64 = aliceEphB64,
            clientIdentityPubBase64 = aliceId.publicKeyBase64,
            clientEphPubBase64 = aliceEphB64,
            clientNonceBase64 = cNonce,
            serverIdentityPubBase64 = bobId.publicKeyBase64,
            serverEphPubBase64 = bobEphB64,
            serverNonceBase64 = sNonce
        )

        val secretMsg = "Hello via encrypted VPN!".toByteArray(Charsets.UTF_8)
        val encrypted = aliceCipher.encrypt(0x10, secretMsg)
        val decrypted = bobCipher.decrypt(0x10, encrypted)
        assertArrayEquals(secretMsg, decrypted)
    }

    @Test
    fun `qr code generation and decoding roundtrip`() {
        val uri = QrCodeHelper.buildInviteUri("10.8.0.5", 42850, "PUBKEY123", "Marina", "TOK99")
        val bmp = QrCodeHelper.generateQrBitmap(uri, 320)
        assertNotNull(bmp)
        val decoded = QrCodeHelper.decodeFromBitmap(bmp!!)
        assertEquals(uri, decoded)
        val parsed = QrCodeHelper.parseInviteUri(decoded!!)
        assertNotNull(parsed)
        assertEquals("10.8.0.5", parsed!!.ip)
        assertEquals(42850, parsed.port)
        assertEquals("Marina", parsed.username)
        assertEquals("TOK99", parsed.inviteToken)
    }

    @Test
    fun `reaction codec handles toggle grouping and clearing`() {
        val (json1, wire1) = ReactionCodec.toggleMyReaction("", "❤️")
        assertEquals("❤️", wire1)
        val json2 = ReactionCodec.applyPeerReaction(json1, "❤️")
        val chipsSame = ReactionCodec.groupedChips(json2)
        assertEquals(1, chipsSame.size)
        assertEquals(2, chipsSame.first().count)
        assertTrue(chipsSame.first().byMe)

        val json3 = ReactionCodec.applyPeerReaction(json1, "😂")
        val chipsDiff = ReactionCodec.groupedChips(json3)
        assertEquals(2, chipsDiff.size)

        val (clearedMe, wireClear) = ReactionCodec.toggleMyReaction(json3, "❤️")
        assertEquals("", wireClear)
        val finalChips = ReactionCodec.groupedChips(clearedMe)
        assertEquals(1, finalChips.size)
        assertEquals("😂", finalChips.first().emoji)
        assertFalse(finalChips.first().byMe)
    }

    @Test
    fun `update checker compares semantic versions and extracts apk asset`() {
        assertTrue(UpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isRemoteVersionNewer("1.2.0", "1.0.9"))
        assertFalse(UpdateChecker.isRemoteVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isRemoteVersionNewer("0.9.9", "1.0.0"))

        val releaseJson = """
            {
              "tag_name": "v1.1.0",
              "html_url": "https://github.com/guritso/chatso/releases/tag/v1.1.0",
              "body": "Bug fixes and video player",
              "assets": [
                {
                  "name": "chatso-1.1.0.apk",
                  "browser_download_url": "https://github.com/guritso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk"
                }
              ]
            }
        """.trimIndent()
        val res = UpdateChecker.parseReleaseJson(releaseJson, "1.0.0")
        assertTrue(res is UpdateCheckResult.UpdateAvailable)
        val avail = res as UpdateCheckResult.UpdateAvailable
        assertEquals("1.1.0", avail.latestVersion)
        assertEquals(
            "https://github.com/guritso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk",
            avail.downloadUrl
        )
    }

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
                    replyToPreview: String?
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
    fun `notification uses monochrome drawable icon and handles missing avatar safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notif = ChatsoNotificationHelper.buildServiceNotification(context, connectedCount = 2)
        assertNotNull(notif)
        assertEquals(R.drawable.ic_notification_chatso, notif.smallIcon.resId)
        ChatsoNotificationHelper.showIncomingMessageNotification(
            context = context,
            peerId = "peer_notif_1",
            senderName = "Guritso",
            preview = "Oi!",
            avatarPath = null,
            localUsername = "Tester"
        )
        ChatsoNotificationHelper.showIncomingReactionNotification(
            context = context,
            peerId = "peer_notif_1",
            messageId = "m_1",
            senderName = "Guritso",
            emoji = "❤️",
            reactedSnippet = "Oi!",
            avatarPath = null,
            localUsername = "Tester"
        )
        NetworkUtils.isIgnoringBatteryOptimizations(context)
    }

    @Test
    fun `conversation notification has shortcutId, mark read, and inline reply remote input`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = org.robolectric.Shadows.shadowOf(nm)

        val peerId = "peer_conv_notif"
        ChatsoNotificationHelper.cancelPeerNotification(context, peerId)
        ChatsoNotificationHelper.showIncomingMessageNotification(
            context = context,
            peerId = peerId,
            senderName = "Marina",
            preview = "Olá!",
            avatarPath = null,
            localUsername = "Você"
        )

        val posted: Notification? = shadowNm.getNotification(peerId.hashCode())
        assertNotNull(posted)
        assertEquals("conv_$peerId", posted!!.shortcutId)
        assertEquals(R.drawable.ic_notification_chatso, posted.smallIcon.resId)
        assertEquals(2, posted.actions?.size)
        val replyAction = posted.actions[1]
        assertNotNull(replyAction.remoteInputs)
        assertEquals(ChatsoNotificationHelper.KEY_TEXT_REPLY, replyAction.remoteInputs[0].resultKey)

        // Verify background mark-read via ChatsoNotificationActionReceiver works when app is backgrounded
        val app = context as ChatsoApplication
        val dao = app.container.database.chatDao()
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)
        app.container.isAppInForeground = false
        val mat = CryptoEngine.createIdentityMaterial()
        app.container.repository.onPeerHandshaked(
            peerId = peerId,
            publicKeyBase64 = mat.publicKeyBase64,
            remoteIp = "10.8.0.55",
            remoteListenPort = 42850,
            username = "Marina",
            avatarBase64 = null
        )
        app.container.repository.onIncomingTextMessage(
            peerId = peerId,
            messageId = "msg_notif_unread_1",
            text = "Olá!",
            selfDestructSec = 0,
            createdAt = System.currentTimeMillis()
        )
        assertEquals(1, dao.getPeerById(peerId)!!.unreadCount)

        app.container.repository.markConversationRead(peerId, fromNotification = true)
        assertEquals(0, dao.getPeerById(peerId)!!.unreadCount)
        assertEquals(DeliveryStatus.READ.name, dao.getMessageById("msg_notif_unread_1")!!.status)

        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)
        ChatsoNotificationHelper.cancelPeerNotification(context, peerId)
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
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
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
                    createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
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
    fun `theme palette defaults to purple persists selection advanced mode and switches app icon`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalIdentityStore(context)

        assertEquals(ThemePalette.PURPLE, store.getSnapshot().themePalette)
        assertFalse(store.getSnapshot().themeAdvanced)
        assertEquals(12, ThemePalette.entries.size)
        assertEquals("1.1.7", BuildConfig.VERSION_NAME)
        assertEquals(9, BuildConfig.VERSION_CODE)

        val lightPrimaries = ThemePalette.entries.map { palette ->
            store.setThemePalette(palette)
            assertEquals(palette, store.getSnapshot().themePalette)
            val scheme = buildChatsoColorScheme(darkTheme = false, palette = palette, advanced = false)
            assertEquals(palette.spec().light.primary, scheme.primary)
            scheme.primary
        }.toSet()

        assertEquals(ThemePalette.entries.size, lightPrimaries.size)

        val darkPrimaries = ThemePalette.entries.map { palette ->
            val scheme = buildChatsoColorScheme(darkTheme = true, palette = palette, advanced = false)
            // Verify dark background is elevated charcoal rather than harsh near-black
            assertTrue(scheme.background.red >= 0.09f)
            scheme.primary
        }.toSet()
        assertEquals(ThemePalette.entries.size, darkPrimaries.size)

        // Verify advanced mode tints backgrounds, surfaces, and secondary colors
        store.setThemeAdvanced(true)
        assertTrue(store.getSnapshot().themeAdvanced)
        val basicDark = buildChatsoColorScheme(darkTheme = true, palette = ThemePalette.ORANGE, advanced = false)
        val advDark = buildChatsoColorScheme(darkTheme = true, palette = ThemePalette.ORANGE, advanced = true)
        assertTrue(basicDark.background != advDark.background)
        assertTrue(basicDark.surface != advDark.surface)
        assertTrue(basicDark.secondaryContainer != advDark.secondaryContainer)

        // Verify default icon is dark-navy DEFAULT_ALIAS_CLASS when full theme is OFF,
        // and switches to the palette alias only when full theme is ON + background flush runs
        assertEquals(
            AppIconManager.DEFAULT_ALIAS_CLASS,
            AppIconManager.resolveTargetAlias(ThemePalette.PURPLE, fullThemeEnabled = false)
        )
        assertEquals(
            AppIconManager.DEFAULT_ALIAS_CLASS,
            AppIconManager.resolveTargetAlias(ThemePalette.ORANGE, fullThemeEnabled = false)
        )
        assertEquals(
            ThemePalette.PURPLE.aliasClassName,
            AppIconManager.resolveTargetAlias(ThemePalette.PURPLE, fullThemeEnabled = true)
        )

        AppIconManager.applyAlias(context, AppIconManager.DEFAULT_ALIAS_CLASS)
        AppIconManager.clearTemporarySuppression()
        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))

        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = true)
        // Still default dark-navy icon while in foreground until background flush runs
        assertTrue(AppIconManager.isDefaultIconActive(context))
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))
        assertFalse(AppIconManager.isDefaultIconActive(context))

        // Turning full theme back OFF schedules revert to dark-navy default icon
        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))

        // Simulate app upgrade where an old palette alias remained ENABLED alongside DEFAULT_ALIAS_CLASS
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context.packageName, ThemePalette.PURPLE.aliasClassName),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertTrue(AppIconManager.isPaletteIconActive(context, ThemePalette.PURPLE))
        AppIconManager.scheduleIconSync(ThemePalette.PURPLE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.PURPLE))
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
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
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
    fun `three node bridge mode relays discovery and e2ee chat between isolated peers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val discA = DiscoveryManager(context, scope)
        val discB = DiscoveryManager(context, scope)
        val discC = DiscoveryManager(context, scope)

        val matA = CryptoEngine.createIdentityMaterial()
        val matB = CryptoEngine.createIdentityMaterial()
        val matC = CryptoEngine.createIdentityMaterial()

        val cHandshakedWithAViaBridge = CompletableFuture<String>()
        val cReceivedTextFromA = CompletableFuture<String>()
        val aReceivedTextFromC = CompletableFuture<String>()
        val aDisconnectedFromC = CompletableFuture<Boolean>()

        fun makeListener(
            onHandshake: (peerId: String, viaBridgePeerId: String) -> Unit = { _, _ -> },
            onDisconnect: (peerId: String) -> Unit = {},
            onText: (peerId: String, text: String) -> Unit = { _, _ -> }
        ) = object : P2PEventListener {
            override suspend fun isKnownPeer(peerId: String) = true
            override suspend fun onPeerHandshaked(
                peerId: String, publicKeyBase64: String, remoteIp: String,
                remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String
            ) {
                onHandshake(peerId, viaBridgePeerId)
            }
            override suspend fun onPeerDisconnected(peerId: String) {
                onDisconnect(peerId)
            }
            override suspend fun onIncomingTextMessage(
                peerId: String, messageId: String, text: String, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ) {
                onText(peerId, text)
            }
            override suspend fun onIncomingFileStart(
                peerId: String, messageId: String, messageType: MessageType, fileName: String,
                mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int,
                createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?
            ): File = File.createTempFile("bridge_unused", ".bin")
            override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
            override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
            override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
            override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
            override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
        }

        val nodeA = P2PNodeManager(
            scope,
            discA,
            makeListener(
                onDisconnect = { peerId ->
                    if (peerId == matC.peerId) aDisconnectedFromC.complete(true)
                },
                onText = { peerId, text ->
                    if (peerId == matC.peerId) aReceivedTextFromC.complete(text)
                }
            )
        )
        val nodeB = P2PNodeManager(scope, discB, makeListener())
        val nodeC = P2PNodeManager(
            scope,
            discC,
            makeListener(
                onHandshake = { peerId, viaBridge ->
                    if (peerId == matA.peerId && viaBridge.isNotBlank()) {
                        cHandshakedWithAViaBridge.complete(viaBridge)
                    }
                },
                onText = { peerId, text ->
                    if (peerId == matA.peerId) cReceivedTextFromA.complete(text)
                }
            )
        )

        try {
            val idA = LocalIdentity(
                isInitialized = true, username = "Alice",
                publicKeyBase64 = matA.publicKeyBase64, wrappedPrivateKeyBase64 = matA.wrappedPrivateKeyBase64,
                peerId = matA.peerId, fingerprint = matA.fingerprint,
                listenPort = 43941, isDiscoverable = true, bridgeEnabled = false
            )
            val idB = LocalIdentity(
                isInitialized = true, username = "BridgeB",
                publicKeyBase64 = matB.publicKeyBase64, wrappedPrivateKeyBase64 = matB.wrappedPrivateKeyBase64,
                peerId = matB.peerId, fingerprint = matB.fingerprint,
                listenPort = 43942, isDiscoverable = true, bridgeEnabled = true
            )
            val idC = LocalIdentity(
                isInitialized = true, username = "Charlie",
                publicKeyBase64 = matC.publicKeyBase64, wrappedPrivateKeyBase64 = matC.wrappedPrivateKeyBase64,
                peerId = matC.peerId, fingerprint = matC.fingerprint,
                listenPort = 43943, isDiscoverable = true, bridgeEnabled = false
            )

            nodeA.updateIdentityAndEnsureServer(idA)
            nodeB.updateIdentityAndEnsureServer(idB)
            nodeC.updateIdentityAndEnsureServer(idC)
            delay(250)

            // Both Alice and Charlie connect directly to BridgeB (e.g. A on localhost, C on VPN)
            assertTrue(nodeA.connectToPeer("127.0.0.1", nodeB.boundPort.value, matB.publicKeyBase64).isSuccess)
            assertTrue(nodeC.connectToPeer("127.0.0.1", nodeB.boundPort.value, matB.publicKeyBase64).isSuccess)
            delay(250)

            // When Alice probes BridgeB's beacon port, Alice discovers Charlie via BridgeB
            discA.applyIdentityPolicy(idA)
            discA.scanNetworkNow(listOf("127.0.0.1" to nodeB.boundPort.value))
            delay(400)
            val discoveredC = discA.discoveredPeers.value[matC.peerId]
            assertNotNull(discoveredC)
            assertEquals(matB.peerId, discoveredC!!.viaBridgePeerId)

            // Alice connects to Charlie via BridgeB and both exchange E2EE messages
            val bridgedId = nodeA.connectViaBridge(matB.peerId, matC.peerId, matC.publicKeyBase64).getOrThrow()
            assertEquals(matC.peerId, bridgedId)
            assertEquals(matB.peerId, cHandshakedWithAViaBridge.get(3, TimeUnit.SECONDS))

            assertTrue(
                nodeA.sendTextMessage(
                    peerId = matC.peerId,
                    messageId = "bridge-msg-1",
                    text = "Hello Charlie across BridgeB!",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            assertEquals("Hello Charlie across BridgeB!", cReceivedTextFromA.get(3, TimeUnit.SECONDS))

            assertTrue(
                nodeC.sendTextMessage(
                    peerId = matA.peerId,
                    messageId = "bridge-msg-2",
                    text = "Hi Alice back via BridgeB!",
                    selfDestructSec = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            assertEquals("Hi Alice back via BridgeB!", aReceivedTextFromC.get(3, TimeUnit.SECONDS))

            // BridgeB telemetry must reflect relayed frames, bytes, and routes between Alice & Charlie
            val bridgeStats = nodeB.bridgeTelemetry.value
            assertTrue(bridgeStats.isEnabled)
            assertEquals(2, bridgeStats.directSessionCount)
            assertTrue(bridgeStats.relayedFramesTotal >= 2L)
            assertTrue(bridgeStats.relayedBytesTotal > 0L)
            assertTrue(bridgeStats.activeRoutes.isNotEmpty())

            // Stopping BridgeB cascades disconnect to Alice's bridged session with Charlie
            nodeB.disconnectAll()
            assertTrue(aDisconnectedFromC.get(3, TimeUnit.SECONDS))
        } finally {
            nodeA.disconnectAll()
            nodeB.disconnectAll()
            nodeC.disconnectAll()
            discA.shutdown()
            discB.shutdown()
            discC.shutdown()
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
}
