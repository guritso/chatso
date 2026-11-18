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
import com.example.network.ChatsoNotificationHelper
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import com.example.network.QrCodeHelper
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
    fun `app name matches Chatso`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Chatso", context.getString(R.string.app_name))
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
              "html_url": "https://github.com/gutitso/chatso/releases/tag/v1.1.0",
              "body": "Bug fixes and video player",
              "assets": [
                {
                  "name": "chatso-1.1.0.apk",
                  "browser_download_url": "https://github.com/gutitso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk"
                }
              ]
            }
        """.trimIndent()
        val res = UpdateChecker.parseReleaseJson(releaseJson, "1.0.0")
        assertTrue(res is UpdateCheckResult.UpdateAvailable)
        val avail = res as UpdateCheckResult.UpdateAvailable
        assertEquals("1.1.0", avail.latestVersion)
        assertEquals(
            "https://github.com/gutitso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk",
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
                    avatarBase64: String?
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
            delay(250)

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
    fun `notification uses monochrome drawable icon and handles missing avatar safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notif = ChatsoNotificationHelper.buildServiceNotification(context, connectedCount = 2)
        assertNotNull(notif)
        assertEquals(R.drawable.ic_notification_chatso, notif.smallIcon.resId)
        ChatsoNotificationHelper.showIncomingMessageNotification(
            context = context,
            peerId = "peer_notif_1",
            senderName = "Marina Silva",
            preview = "Oi!",
            avatarPath = null
        )
        NetworkUtils.isIgnoringBatteryOptimizations(context)
    }
}
