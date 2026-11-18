package com.example.data

import android.content.Context
import android.net.Uri
import com.example.crypto.CryptoEngine
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val chatDao: ChatDao,
    val identityStore: LocalIdentityStore,
    val mediaStorage: MediaStorageHelper
) : P2PEventListener {

    val discoveryManager = DiscoveryManager(context.applicationContext, scope)
    val nodeManager = P2PNodeManager(scope, discoveryManager, this)

    val allPeersFlow: Flow<List<PeerEntity>> = chatDao.observeAllPeers()

    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()

    @Volatile
    var currentlyOpenPeerId: String? = null

    var onIncomingNotification: ((peerId: String, senderName: String, preview: String) -> Unit)? = null

    init {
        nodeManager.setAvatarProvider {
            val snap = identityStore.getSnapshot()
            mediaStorage.encodeAvatarBase64(snap.avatarPath)
        }
        scope.launch(Dispatchers.IO) {
            identityStore.identityFlow.collectLatest { identity ->
                nodeManager.updateIdentityAndEnsureServer(identity)
            }
        }
        // Self-destruct message reaper loop (runs every second)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                cleanupExpiredMessages()
                delay(1000L)
            }
        }
    }

    fun observePeer(peerId: String): Flow<PeerEntity?> = chatDao.observePeer(peerId)

    fun observeMessages(peerId: String, limit: Int): Flow<List<MessageEntity>> =
        chatDao.observeMessagesForPeer(peerId, limit)

    suspend fun connectDirect(
        ip: String,
        port: Int,
        expectedPubKeyBase64: String = "",
        inviteToken: String = ""
    ): Result<String> {
        return nodeManager.connectToPeer(ip, port, expectedPubKeyBase64, inviteToken)
    }

    suspend fun ensurePeerConnected(peerId: String): Boolean {
        if (nodeManager.connectedPeers.value.contains(peerId)) return true
        val peer = chatDao.getPeerById(peerId) ?: return false
        if (peer.lastIp.isBlank()) return false
        return nodeManager.connectToPeer(
            host = peer.lastIp,
            port = peer.lastPort,
            expectedPubKeyBase64 = peer.publicKeyBase64
        ).isSuccess
    }

    suspend fun sendText(peerId: String, rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sdSec = peer.selfDestructSeconds

        val entity = MessageEntity(
            messageId = msgId,
            peerId = peerId,
            isOutgoing = true,
            type = MessageType.TEXT.name,
            textContent = text,
            transferProgress = 100,
            status = DeliveryStatus.SENDING.name,
            selfDestructSeconds = sdSec,
            createdAt = now
        )
        chatDao.upsertMessage(entity)
        chatDao.upsertPeer(
            peer.copy(
                lastMessagePreview = text,
                lastActivityAt = now
            )
        )

        var connected = ensurePeerConnected(peerId)
        var ok = if (connected) {
            nodeManager.sendTextMessage(peerId, msgId, text, sdSec, now)
        } else {
            false
        }
        if (!ok) {
            // Reconnect once in case cached VPN socket dropped silently
            connected = ensurePeerConnected(peerId)
            if (connected) {
                ok = nodeManager.sendTextMessage(peerId, msgId, text, sdSec, now)
            }
        }
        chatDao.updateMessageStatus(
            msgId,
            if (ok) DeliveryStatus.SENT.name else DeliveryStatus.FAILED.name
        )
        return ok
    }

    suspend fun scanKnownAndNetworkPeers() {
        val known = chatDao.getAllPeersOnce()
            .filter { it.lastIp.isNotBlank() }
            .map { it.lastIp to it.lastPort }
        discoveryManager.scanNetworkNow(known)
    }

    suspend fun sendUriAttachment(peerId: String, uri: Uri, forceImage: Boolean): Boolean {
        val copied = mediaStorage.copyUriToPrivateMedia(
            uri,
            if (forceImage) "photo" else "file"
        ) ?: return false
        val msgType = if (forceImage || copied.mimeType.startsWith("image/")) {
            MessageType.IMAGE
        } else {
            MessageType.FILE
        }
        return sendPreparedFile(
            peerId = peerId,
            messageType = msgType,
            file = copied.localFile,
            fileName = copied.fileName,
            mimeType = copied.mimeType,
            audioDurationMs = 0L
        )
    }

    suspend fun sendVoiceRecording(peerId: String, audioFile: File, durationMs: Long): Boolean {
        val actualDur = durationMs.takeIf { it > 0L } ?: mediaStorage.readAudioDurationMs(audioFile)
        return sendPreparedFile(
            peerId = peerId,
            messageType = MessageType.VOICE,
            file = audioFile,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            audioDurationMs = actualDur
        )
    }

    private suspend fun sendPreparedFile(
        peerId: String,
        messageType: MessageType,
        file: File,
        fileName: String,
        mimeType: String,
        audioDurationMs: Long
    ): Boolean {
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sdSec = peer.selfDestructSeconds
        val preview = when (messageType) {
            MessageType.IMAGE -> "Photo: $fileName"
            MessageType.VOICE -> "Voice message (${NetworkUtils.formatDurationMs(audioDurationMs)})"
            else -> "File: $fileName"
        }

        val entity = MessageEntity(
            messageId = msgId,
            peerId = peerId,
            isOutgoing = true,
            type = messageType.name,
            textContent = preview,
            mediaLocalPath = file.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            fileSizeBytes = file.length(),
            audioDurationMs = audioDurationMs,
            transferProgress = 1,
            status = DeliveryStatus.SENDING.name,
            selfDestructSeconds = sdSec,
            createdAt = now
        )
        chatDao.upsertMessage(entity)
        chatDao.upsertPeer(
            peer.copy(
                lastMessagePreview = preview,
                lastActivityAt = now
            )
        )

        val connected = ensurePeerConnected(peerId)
        val ok = if (connected) {
            nodeManager.sendMediaOrFile(
                peerId = peerId,
                messageId = msgId,
                messageType = messageType,
                file = file,
                fileName = fileName,
                mimeType = mimeType,
                audioDurationMs = audioDurationMs,
                selfDestructSec = sdSec,
                createdAt = now,
                onProgress = { pct ->
                    chatDao.updateTransferProgress(msgId, pct, DeliveryStatus.SENDING.name)
                }
            )
        } else {
            false
        }
        chatDao.updateTransferProgress(
            msgId,
            if (ok) 100 else 0,
            if (ok) DeliveryStatus.SENT.name else DeliveryStatus.FAILED.name
        )
        return ok
    }

    suspend fun markConversationRead(peerId: String) {
        chatDao.clearUnread(peerId)
        val unread = chatDao.getUnreadIncomingMessages(peerId)
        val now = System.currentTimeMillis()
        for (msg in unread) {
            if (msg.transferProgress < 100) continue
            val expiresAt = if (msg.selfDestructSeconds > 0) {
                now + msg.selfDestructSeconds * 1000L
            } else {
                null
            }
            chatDao.markMessageRead(msg.messageId, DeliveryStatus.READ.name, now, expiresAt)
            nodeManager.sendReadReceipt(peerId, msg.messageId)
        }
    }

    suspend fun setSelfDestructTimer(peerId: String, seconds: Int) {
        chatDao.updatePeerSelfDestruct(peerId, seconds)
        nodeManager.sendSelfDestructConfig(peerId, seconds)
    }

    suspend fun markPeerVerified(peerId: String, verified: Boolean) {
        chatDao.setPeerVerified(peerId, verified)
    }

    suspend fun deleteConversation(peerId: String) {
        chatDao.deleteAllMessagesForPeer(peerId)
        chatDao.deletePeer(peerId)
    }

    override suspend fun isKnownPeer(peerId: String): Boolean {
        return chatDao.getPeerById(peerId) != null
    }

    override suspend fun onPeerHandshaked(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?
    ) {
        val existing = chatDao.getPeerById(peerId)
        val avatarPath = if (!avatarBase64.isNullOrBlank()) {
            mediaStorage.savePeerAvatarBase64(peerId, avatarBase64) ?: existing?.avatarPath
        } else {
            existing?.avatarPath
        }
        val keyChanged = existing != null &&
            existing.publicKeyBase64.isNotBlank() &&
            existing.publicKeyBase64 != publicKeyBase64

        val resolvedName = if (username.isNotBlank() && username != remoteIp) {
            username
        } else {
            existing?.username ?: username.ifBlank { "P2P Contact" }
        }

        val updated = PeerEntity(
            peerId = peerId,
            username = resolvedName,
            publicKeyBase64 = publicKeyBase64,
            fingerprint = CryptoEngine.formatFingerprintFromBase64(publicKeyBase64),
            avatarPath = avatarPath,
            lastIp = remoteIp,
            lastPort = remoteListenPort,
            isVerified = if (keyChanged) false else (existing?.isVerified ?: false),
            keyChangedWarning = keyChanged,
            selfDestructSeconds = existing?.selfDestructSeconds ?: 0,
            lastMessagePreview = existing?.lastMessagePreview?.ifBlank { "E2EE channel established" }
                ?: "E2EE channel established",
            lastActivityAt = existing?.lastActivityAt ?: System.currentTimeMillis(),
            unreadCount = existing?.unreadCount ?: 0
        )
        chatDao.upsertPeer(updated)
    }

    override suspend fun onPeerDisconnected(peerId: String) {
        _typingPeers.update { it - peerId }
    }

    override suspend fun onIncomingTextMessage(
        peerId: String,
        messageId: String,
        text: String,
        selfDestructSec: Int,
        createdAt: Long
    ) {
        _typingPeers.update { it - peerId }
        val isOpen = currentlyOpenPeerId == peerId
        val now = System.currentTimeMillis()
        val expiresAt = if (isOpen && selfDestructSec > 0) {
            now + selfDestructSec * 1000L
        } else {
            null
        }
        val msg = MessageEntity(
            messageId = messageId,
            peerId = peerId,
            isOutgoing = false,
            type = MessageType.TEXT.name,
            textContent = text,
            transferProgress = 100,
            status = if (isOpen) DeliveryStatus.READ.name else DeliveryStatus.DELIVERED.name,
            selfDestructSeconds = selfDestructSec,
            createdAt = createdAt,
            readAt = if (isOpen) now else null,
            expiresAt = expiresAt
        )
        chatDao.upsertMessage(msg)
        val peer = chatDao.getPeerById(peerId)
        if (peer != null) {
            chatDao.upsertPeer(
                peer.copy(
                    lastMessagePreview = text,
                    lastActivityAt = now,
                    unreadCount = if (isOpen) 0 else peer.unreadCount + 1
                )
            )
        }
        if (isOpen) {
            nodeManager.sendReadReceipt(peerId, messageId)
        } else {
            val senderName = peer?.username ?: "Chatso"
            onIncomingNotification?.invoke(peerId, senderName, text)
        }
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
    ): File {
        val targetFile = mediaStorage.createIncomingMediaFile(messageId, fileName)
        val preview = when (messageType) {
            MessageType.IMAGE -> "Receiving photo: $fileName"
            MessageType.VOICE -> "Receiving voice message…"
            else -> "Receiving file: $fileName"
        }
        val msg = MessageEntity(
            messageId = messageId,
            peerId = peerId,
            isOutgoing = false,
            type = messageType.name,
            textContent = preview,
            mediaLocalPath = targetFile.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            fileSizeBytes = totalBytes,
            audioDurationMs = audioDurationMs,
            transferProgress = 1,
            status = DeliveryStatus.SENDING.name,
            selfDestructSeconds = selfDestructSec,
            createdAt = createdAt
        )
        chatDao.upsertMessage(msg)
        return targetFile
    }

    override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {
        chatDao.updateTransferProgress(messageId, progress, DeliveryStatus.SENDING.name)
    }

    override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {
        val existing = chatDao.getMessageById(messageId) ?: return
        val isOpen = currentlyOpenPeerId == peerId
        val now = System.currentTimeMillis()
        val expiresAt = if (isOpen && existing.selfDestructSeconds > 0) {
            now + existing.selfDestructSeconds * 1000L
        } else {
            null
        }
        val finalPreview = when (existing.type) {
            MessageType.IMAGE.name -> "Photo: ${existing.fileName ?: "image"}"
            MessageType.VOICE.name -> "Voice message (${NetworkUtils.formatDurationMs(existing.audioDurationMs)})"
            else -> "File: ${existing.fileName ?: file.name}"
        }
        chatDao.upsertMessage(
            existing.copy(
                textContent = finalPreview,
                mediaLocalPath = file.absolutePath,
                transferProgress = 100,
                status = if (isOpen) DeliveryStatus.READ.name else DeliveryStatus.DELIVERED.name,
                readAt = if (isOpen) now else null,
                expiresAt = expiresAt
            )
        )
        val peer = chatDao.getPeerById(peerId)
        if (peer != null) {
            chatDao.upsertPeer(
                peer.copy(
                    lastMessagePreview = finalPreview,
                    lastActivityAt = now,
                    unreadCount = if (isOpen) 0 else peer.unreadCount + 1
                )
            )
        }
        if (isOpen) {
            nodeManager.sendReadReceipt(peerId, messageId)
        } else {
            val senderName = peer?.username ?: "Chatso"
            onIncomingNotification?.invoke(peerId, senderName, finalPreview)
        }
    }

    override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {
        val msg = chatDao.getMessageById(messageId) ?: return
        if (status == DeliveryStatus.READ) {
            val now = System.currentTimeMillis()
            val expiresAt = if (msg.selfDestructSeconds > 0) {
                now + msg.selfDestructSeconds * 1000L
            } else {
                null
            }
            chatDao.markMessageRead(messageId, DeliveryStatus.READ.name, now, expiresAt)
        } else {
            if (msg.status != DeliveryStatus.READ.name) {
                chatDao.updateMessageStatus(messageId, status.name)
            }
        }
    }

    override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {
        _typingPeers.update { if (isTyping) it + peerId else it - peerId }
    }

    override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {
        chatDao.updatePeerSelfDestruct(peerId, seconds)
    }

    private suspend fun cleanupExpiredMessages() {
        val now = System.currentTimeMillis()
        val expired = chatDao.getExpiredMessages(now)
        for (msg in expired) {
            mediaStorage.deleteMediaFile(msg.mediaLocalPath)
            chatDao.deleteMessageById(msg.messageId)
        }
    }

    fun shutdown() {
        discoveryManager.shutdown()
        nodeManager.disconnectAll()
    }
}
