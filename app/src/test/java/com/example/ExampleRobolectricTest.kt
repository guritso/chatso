package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.AppLanguage
import com.example.data.ChatDatabase
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.LocaleHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.network.ChatsoNotificationHelper
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import com.example.network.QrCodeHelper
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `real tcp socket e2ee chat invisible mode and fast discovery`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receivedText = CompletableFuture<String>()

        try {
            val aliceMat = CryptoEngine.createIdentityMaterial()
            val bobMat = CryptoEngine.createIdentityMaterial()

            val noopListener = object : P2PEventListener {
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
                override suspend fun onIncomingTextMessage(
                    peerId: String,
                    messageId: String,
                    text: String,
                    selfDestructSec: Int,
                    createdAt: Long
                ) {
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
                    createdAt: Long
                ): File = File.createTempFile("chatso_test", ".bin")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }

            val discA = DiscoveryManager(context, scope)
            val discB = DiscoveryManager(context, scope)
            val nodeA = P2PNodeManager(scope, discA, noopListener)
            val nodeB = P2PNodeManager(scope, discB, noopListener)

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
            // Bob starts in Invisible Mode (isDiscoverable = false)
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

            nodeA.updateIdentityAndEnsureServer(aliceIdentity)
            nodeB.updateIdentityAndEnsureServer(bobIdentity)
            delay(250)

            val bobPort = nodeB.boundPort.value
            val alicePort = nodeA.boundPort.value

            // 1. Verify Invisible Mode drops discovery probe without revealing identity
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

            // 2. Verify fast discovery scan finishes well below 4s and discovers visible Alice only
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

            // 3. Direct IP connection still succeeds with full E2EE handshake & immediate message delivery
            val connRes = nodeA.connectToPeer("127.0.0.1", bobPort, bobMat.publicKeyBase64)
            assertTrue(connRes.isSuccess)
            val connectedPeerId = connRes.getOrThrow()
            val sent = nodeA.sendTextMessage(
                peerId = connectedPeerId,
                messageId = "msg-001",
                text = "Secret message over P2P socket!",
                selfDestructSec = 30,
                createdAt = System.currentTimeMillis()
            )
            assertTrue(sent)
            assertEquals("Secret message over P2P socket!", receivedText.get(3, TimeUnit.SECONDS))

            nodeA.disconnectAll()
            nodeB.disconnectAll()
            discA.shutdown()
            discB.shutdown()
        } finally {
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
        // Newest message must be at index 0 for reverseLayout LazyColumn instant bottom open
        assertEquals("m_220", page1.first().messageId)
        assertEquals("m_71", page1.last().messageId)

        val page2 = dao.observeMessagesForPeer(peerId, limit = 300).first()
        assertEquals(220, page2.size)
        assertEquals("m_1", page2.last().messageId)

        dao.deleteAllMessagesForPeer(peerId)
    }

    @Test
    fun `background service notification and battery check work cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notif = ChatsoNotificationHelper.buildServiceNotification(context, connectedCount = 2)
        assertNotNull(notif)
        // Simply ensure battery optimization query returns without exception
        NetworkUtils.isIgnoringBatteryOptimizations(context)
    }
}
