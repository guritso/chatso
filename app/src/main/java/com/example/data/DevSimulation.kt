package com.example.data

import android.content.Context
import com.example.crypto.CryptoEngine
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Listener que ignora todos os eventos; base dos nos auxiliares do teste de bridge. */
internal open class NoOpP2PEventListener(private val cacheDir: File) : P2PEventListener {
    override suspend fun isKnownPeer(peerId: String): Boolean = true
    override suspend fun onPeerHandshaked(peerId: String, publicKeyBase64: String, remoteIp: String, remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String) {}
    override suspend fun onPeerDisconnected(peerId: String) {}
    override suspend fun onIncomingTextMessage(peerId: String, messageId: String, text: String, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?) {}
    override suspend fun onIncomingFileStart(peerId: String, messageId: String, messageType: MessageType, fileName: String, mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?, mediaWidth: Int, mediaHeight: Int): File = File(cacheDir, "noop_$messageId")
    override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
    override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
    override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
    override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
    override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
}

/** Imagem de exemplo usada pelo modo dev para simular foto/figurinha recebida. */
internal fun buildDevPlaceholderBitmap(asSticker: Boolean, now: Long): android.graphics.Bitmap {
    return if (asSticker) {
        android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF7C3AED.toInt()
            }
            canvas.drawCircle(128f, 128f, 112f, bgPaint)
            val eyePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
            }
            canvas.drawCircle(90f, 100f, 14f, eyePaint)
            canvas.drawCircle(166f, 100f, 14f, eyePaint)
            val smilePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 14f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawArc(76f, 92f, 180f, 184f, 20f, 140f, false, smilePaint)
        }
    } else {
        android.graphics.Bitmap.createBitmap(640, 420, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val bgPaint = android.graphics.Paint().apply { color = 0xFF1E293B.toInt() }
            canvas.drawRect(0f, 0f, 640f, 420f, bgPaint)
            val accentPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0F766E.toInt()
            }
            canvas.drawCircle(520f, 110f, 130f, accentPaint)
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                isFakeBoldText = true
            }
            canvas.drawText("CHATSO DEV PHOTO", 44f, 200f, textPaint)
            textPaint.textSize = 24f
            textPaint.isFakeBoldText = false
            textPaint.color = 0xFF94A3B8.toInt()
            canvas.drawText(NetworkUtils.formatTimestamp(now), 44f, 248f, textPaint)
        }
    }

}

/** Sobe dois nos locais (A e C) e valida A <-> bridge <-> C com E2EE. */
internal suspend fun runLoopbackBridgeTest(
    appContext: Context,
    scope: CoroutineScope,
    chatDao: ChatDao,
    identityStore: LocalIdentityStore,
    nodeManager: P2PNodeManager,
    transientTestPeerIds: MutableSet<String>
): Result<String> {
    var identity = identityStore.getSnapshot()
    if (!identity.isInitialized) {
        return Result.failure(IllegalStateException("Local identity not initialized"))
    }
    if (!identity.bridgeEnabled) {
        identityStore.setBridgeEnabled(true)
        identity = identityStore.getSnapshot()
    }
    val serverDeadline = System.currentTimeMillis() + 2500L
    while (!nodeManager.serverRunning.value && System.currentTimeMillis() < serverDeadline) {
        delay(50L)
    }
    if (!nodeManager.serverRunning.value) {
        return Result.failure(IllegalStateException("P2P server not running"))
    }
    val myPort = nodeManager.boundPort.value
    val matA = CryptoEngine.createIdentityMaterial()
    val matC = CryptoEngine.createIdentityMaterial()
    transientTestPeerIds.add(matA.peerId)
    transientTestPeerIds.add(matC.peerId)

    val receivedOnC = CompletableDeferred<String>()
    val discA = DiscoveryManager(appContext, scope)
    val discC = DiscoveryManager(appContext, scope)
    val nodeA = P2PNodeManager(
        scope = scope,
        discoveryManager = discA,
        listener = NoOpP2PEventListener(appContext.cacheDir)
    )
    val nodeC = P2PNodeManager(
        scope = scope,
        discoveryManager = discC,
        listener = object : NoOpP2PEventListener(appContext.cacheDir) {
            override suspend fun onIncomingTextMessage(peerId: String, messageId: String, text: String, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?) {
                receivedOnC.complete(text)
            }
        }
    )

    return try {
        nodeA.updateIdentityAndEnsureServer(
            LocalIdentity(
                isInitialized = true,
                username = "Sandbox Node A",
                publicKeyBase64 = matA.publicKeyBase64,
                wrappedPrivateKeyBase64 = matA.wrappedPrivateKeyBase64,
                peerId = matA.peerId,
                fingerprint = matA.fingerprint,
                listenPort = 0,
                isDiscoverable = false
            )
        )
        nodeC.updateIdentityAndEnsureServer(
            LocalIdentity(
                isInitialized = true,
                username = "Sandbox Node C",
                publicKeyBase64 = matC.publicKeyBase64,
                wrappedPrivateKeyBase64 = matC.wrappedPrivateKeyBase64,
                peerId = matC.peerId,
                fingerprint = matC.fingerprint,
                listenPort = 0,
                isDiscoverable = false
            )
        )

        val resA = nodeA.connectToPeer("127.0.0.1", myPort, identity.publicKeyBase64, identity.qrInviteToken)
        val resC = nodeC.connectToPeer("127.0.0.1", myPort, identity.publicKeyBase64, identity.qrInviteToken)
        if (resA.isFailure || resC.isFailure) {
            return Result.failure(resA.exceptionOrNull() ?: resC.exceptionOrNull() ?: IllegalStateException("Direct handshake failed"))
        }

        // Wait until both sessions complete PROFILE_SYNC on our bridge
        val regDeadline = System.currentTimeMillis() + 2000L
        while (
            (!nodeManager.connectedPeers.value.contains(matA.peerId) ||
                !nodeManager.connectedPeers.value.contains(matC.peerId)) &&
            System.currentTimeMillis() < regDeadline
        ) {
            delay(40L)
        }

        val bridged = nodeA.connectViaBridge(
            bridgePeerId = identity.peerId,
            targetPeerId = matC.peerId,
            expectedPubKeyBase64 = matC.publicKeyBase64
        )
        if (bridged.isFailure) {
            return Result.failure(bridged.exceptionOrNull() ?: IllegalStateException("Bridged handshake failed"))
        }

        val testPayload = "Chatso Bridge Loopback E2EE Test"
        val sent = nodeA.sendTextMessage(
            peerId = matC.peerId,
            messageId = UUID.randomUUID().toString(),
            text = testPayload,
            selfDestructSec = 0,
            createdAt = System.currentTimeMillis()
        )
        if (!sent) {
            return Result.failure(IllegalStateException("Failed to send relayed message"))
        }
        val delivered = withTimeoutOrNull(4000L) { receivedOnC.await() }
        if (delivered == testPayload) {
            delay(150L)
            Result.success("A ↔ Bridge ↔ C OK")
        } else {
            Result.failure(IllegalStateException("Timed out waiting for bridged relay"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        nodeA.disconnectAll()
        nodeC.disconnectAll()
        discA.shutdown()
        discC.shutdown()
        delay(100L)
        transientTestPeerIds.remove(matA.peerId)
        transientTestPeerIds.remove(matC.peerId)
        chatDao.deletePeer(matA.peerId)
        chatDao.deletePeer(matC.peerId)
    }
}
