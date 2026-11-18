package com.example.data

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.example.crypto.CryptoEngine
import com.example.network.DiscoveryManager
import com.example.network.FileSendResult
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    val scope: CoroutineScope,
    private val chatDao: ChatDao,
    val identityStore: LocalIdentityStore,
    val mediaStorage: MediaStorageHelper,
    private val isAppInForegroundProvider: () -> Boolean = { true }
) : P2PEventListener {

    private val appContext = context.applicationContext
    val discoveryManager = DiscoveryManager(appContext, scope)
    val nodeManager = P2PNodeManager(scope, discoveryManager, this)

    val allPeersFlow: Flow<List<PeerEntity>> = chatDao.observeAllPeers()

    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()

    private val transfersMap = ConcurrentHashMap<String, ActiveTransferInfo>()
    private val lastDbProgressSyncMs = ConcurrentHashMap<String, Long>()
    private val _activeTransfers = MutableStateFlow<List<ActiveTransferInfo>>(emptyList())
    val activeTransfers: StateFlow<List<ActiveTransferInfo>> = _activeTransfers.asStateFlow()

    private val wakeLockGuard = Any()
    private var transferWakeLock: PowerManager.WakeLock? = null
    private var transferWifiLock: WifiManager.WifiLock? = null

    @Volatile
    var currentlyOpenPeerId: String? = null

    var onIncomingNotification: (
        (peerId: String, senderName: String, preview: String, avatarPath: String?) -> Unit
    )? = null

    var onIncomingReactionNotification: (
        (
            peerId: String,
            messageId: String,
            senderName: String,
            emoji: String,
            messageSnippet: String,
            avatarPath: String?
        ) -> Unit
    )? = null

    var onTransferUnavailableNotice: ((peerId: String, messageId: String) -> Unit)? = null

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
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                cleanupExpiredMessages()
                delay(1000L)
            }
        }
    }

    private fun syncTransferWakeLocks(hasActiveTransfers: Boolean) {
        synchronized(wakeLockGuard) {
            if (hasActiveTransfers) {
                runCatching {
                    if (transferWakeLock == null) {
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        transferWakeLock = pm?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Chatso::ActiveTransferWakeLock"
                        )?.apply { setReferenceCounted(false) }
                    }
                    if (transferWakeLock?.isHeld == false) {
                        transferWakeLock?.acquire(30 * 60 * 1000L)
                    }
                }
                runCatching {
                    if (transferWifiLock == null) {
                        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            @Suppress("DEPRECATION")
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        transferWifiLock = wm?.createWifiLock(mode, "Chatso::ActiveTransferWifiLock")
                            ?.apply { setReferenceCounted(false) }
                    }
                    if (transferWifiLock?.isHeld == false) {
                        transferWifiLock?.acquire()
                    }
                }
            } else {
                runCatching {
                    if (transferWakeLock?.isHeld == true) transferWakeLock?.release()
                }
                runCatching {
                    if (transferWifiLock?.isHeld == true) transferWifiLock?.release()
                }
            }
        }
    }

    private fun publishTransfersSnapshot() {
        val snapshot = transfersMap.values.sortedBy { it.fileName }
        _activeTransfers.value = snapshot
        syncTransferWakeLocks(snapshot.isNotEmpty())
    }

    private fun updateTransferEntry(info: ActiveTransferInfo) {
        transfersMap[info.messageId] = info
        publishTransfersSnapshot()
    }

    private fun removeTransferEntry(messageId: String) {
        lastDbProgressSyncMs.remove(messageId)
        if (transfersMap.remove(messageId) != null) {
            publishTransfersSnapshot()
        }
    }

    private fun scheduleThrottledDbProgress(messageId: String, pct: Int) {
        val now = System.currentTimeMillis()
        val prev = lastDbProgressSyncMs[messageId] ?: 0L
        if (now - prev >= 350L) {
            lastDbProgressSyncMs[messageId] = now
            scope.launch(Dispatchers.IO) {
                chatDao.updateOngoingTransferProgress(messageId, pct)
            }
        }
    }

    fun observePeer(peerId: String): Flow<PeerEntity?> = chatDao.observePeer(peerId)

    fun observeMessages(peerId: String, limit: Int): Flow<List<MessageEntity>> =
        chatDao.observeMessagesForPeer(peerId, limit)

    fun onAppForegroundChanged(inForeground: Boolean) {
        nodeManager.setLocalAppInForeground(inForeground)
        if (inForeground) {
            val openPeer = currentlyOpenPeerId ?: return
            scope.launch(Dispatchers.IO) {
                markConversationRead(openPeer)
            }
        }
    }

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

    private fun buildReplyMeta(replyTo: MessageEntity?): Triple<String?, String?, String?> {
        if (replyTo == null) return Triple(null, null, null)
        val authorTag = if (replyTo.isOutgoing) ReplySenderTags.ME else ReplySenderTags.PEER
        val wireAuthorType = if (replyTo.isOutgoing) "ME" else "PEER"
        val preview = when (replyTo.type) {
            MessageType.IMAGE.name -> "Photo"
            MessageType.VIDEO.name -> "Video"
            MessageType.VOICE.name -> "Voice (${NetworkUtils.formatDurationMs(replyTo.audioDurationMs)})"
            MessageType.FILE.name -> replyTo.fileName ?: "File"
            else -> replyTo.textContent
        }.take(100)
        return Triple(replyTo.messageId, "$authorTag|$wireAuthorType", preview)
    }

    suspend fun sendText(
        peerId: String,
        rawText: String,
        replyTo: MessageEntity? = null
    ): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sdSec = peer.selfDestructSeconds
        val (replyId, combinedTag, replyPreview) = buildReplyMeta(replyTo)
        val localSenderTag = combinedTag?.substringBefore('|')
        val wireAuthorType = combinedTag?.substringAfter('|')

        val entity = MessageEntity(
            messageId = msgId,
            peerId = peerId,
            isOutgoing = true,
            type = MessageType.TEXT.name,
            textContent = text,
            transferProgress = 100,
            status = DeliveryStatus.SENDING.name,
            selfDestructSeconds = sdSec,
            createdAt = now,
            replyToMessageId = replyId,
            replyToSenderTag = localSenderTag,
            replyToPreview = replyPreview
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
            nodeManager.sendTextMessage(
                peerId = peerId,
                messageId = msgId,
                text = text,
                selfDestructSec = sdSec,
                createdAt = now,
                replyToMessageId = replyId,
                replyToAuthorType = wireAuthorType,
                replyToPreview = replyPreview
            )
        } else {
            false
        }
        if (!ok) {
            connected = ensurePeerConnected(peerId)
            if (connected) {
                ok = nodeManager.sendTextMessage(
                    peerId = peerId,
                    messageId = msgId,
                    text = text,
                    selfDestructSec = sdSec,
                    createdAt = now,
                    replyToMessageId = replyId,
                    replyToAuthorType = wireAuthorType,
                    replyToPreview = replyPreview
                )
            }
        }
        chatDao.updateMessageStatus(
            msgId,
            if (ok) DeliveryStatus.SENT.name else DeliveryStatus.FAILED.name
        )
        return ok
    }

    suspend fun toggleReaction(peerId: String, messageId: String, emoji: String) {
        val msg = chatDao.getMessageById(messageId) ?: return
        val (nextJson, outgoingEmoji) = ReactionCodec.toggleMyReaction(msg.reactionsJson, emoji)
        chatDao.updateMessageReactions(messageId, nextJson)
        if (ensurePeerConnected(peerId)) {
            nodeManager.sendReaction(peerId, messageId, outgoingEmoji)
        }
    }

    suspend fun scanKnownAndNetworkPeers() {
        val known = chatDao.getAllPeersOnce()
            .filter { it.lastIp.isNotBlank() }
            .map { it.lastIp to it.lastPort }
        discoveryManager.scanNetworkNow(known)
    }

    suspend fun sendUriAttachment(
        peerId: String,
        uri: Uri,
        forceImage: Boolean = false,
        replyTo: MessageEntity? = null
    ): Boolean {
        val copied = mediaStorage.copyUriToPrivateMedia(
            uri,
            if (forceImage) "photo" else "media"
        ) ?: return false
        val msgType = when {
            forceImage -> MessageType.IMAGE
            copied.mimeType.startsWith("image/") -> MessageType.IMAGE
            copied.mimeType.startsWith("video/") -> MessageType.VIDEO
            else -> MessageType.FILE
        }
        val videoDurMs = if (msgType == MessageType.VIDEO) {
            mediaStorage.readAudioDurationMs(copied.localFile)
        } else {
            0L
        }
        return sendPreparedFile(
            peerId = peerId,
            messageType = msgType,
            file = copied.localFile,
            fileName = copied.fileName,
            mimeType = copied.mimeType,
            audioDurationMs = videoDurMs,
            replyTo = replyTo
        )
    }

    suspend fun sendVoiceRecording(
        peerId: String,
        audioFile: File,
        durationMs: Long,
        replyTo: MessageEntity? = null
    ): Boolean {
        val actualDur = durationMs.takeIf { it > 0L } ?: mediaStorage.readAudioDurationMs(audioFile)
        return sendPreparedFile(
            peerId = peerId,
            messageType = MessageType.VOICE,
            file = audioFile,
            fileName = audioFile.name,
            mimeType = "audio/mp4",
            audioDurationMs = actualDur,
            replyTo = replyTo
        )
    }

    private suspend fun sendPreparedFile(
        peerId: String,
        messageType: MessageType,
        file: File,
        fileName: String,
        mimeType: String,
        audioDurationMs: Long,
        replyTo: MessageEntity? = null
    ): Boolean {
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sdSec = peer.selfDestructSeconds
        val preview = when (messageType) {
            MessageType.IMAGE -> "Photo: $fileName"
            MessageType.VIDEO -> "Video: $fileName"
            MessageType.VOICE -> "Voice message (${NetworkUtils.formatDurationMs(audioDurationMs)})"
            else -> "File: $fileName"
        }
        val (replyId, combinedTag, replyPreview) = buildReplyMeta(replyTo)
        val localSenderTag = combinedTag?.substringBefore('|')
        val wireAuthorType = combinedTag?.substringAfter('|')
        val totalBytes = file.length()

        val entity = MessageEntity(
            messageId = msgId,
            peerId = peerId,
            isOutgoing = true,
            type = messageType.name,
            textContent = preview,
            mediaLocalPath = file.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            fileSizeBytes = totalBytes,
            audioDurationMs = audioDurationMs,
            transferProgress = 1,
            status = DeliveryStatus.SENDING.name,
            selfDestructSeconds = sdSec,
            createdAt = now,
            replyToMessageId = replyId,
            replyToSenderTag = localSenderTag,
            replyToPreview = replyPreview
        )
        chatDao.upsertMessage(entity)
        chatDao.upsertPeer(
            peer.copy(
                lastMessagePreview = preview,
                lastActivityAt = now
            )
        )

        return streamOutgoingMessage(entity, file) != FileSendResult.FAILED
    }

    private suspend fun streamOutgoingMessage(entity: MessageEntity, file: File): FileSendResult {
        val msgId = entity.messageId
        val peerId = entity.peerId
        val fileName = entity.fileName ?: file.name
        val totalBytes = file.length()
        val messageType = MessageType.entries.firstOrNull { it.name == entity.type } ?: MessageType.FILE
        val wireAuthorType = when (entity.replyToSenderTag) {
            ReplySenderTags.ME -> "ME"
            ReplySenderTags.PEER -> "PEER"
            else -> null
        }

        nodeManager.clearTransferCancellation(msgId)
        if (messageType != MessageType.VOICE || totalBytes > 64 * 1024) {
            updateTransferEntry(
                ActiveTransferInfo(
                    messageId = msgId,
                    peerId = peerId,
                    fileName = fileName,
                    progress = 1,
                    isOutgoing = true,
                    totalBytes = totalBytes
                )
            )
        }

        val result = try {
            val connected = ensurePeerConnected(peerId)
            if (connected) {
                nodeManager.sendMediaOrFile(
                    peerId = peerId,
                    messageId = msgId,
                    messageType = messageType,
                    file = file,
                    fileName = fileName,
                    mimeType = entity.mimeType ?: "application/octet-stream",
                    audioDurationMs = entity.audioDurationMs,
                    selfDestructSec = entity.selfDestructSeconds,
                    createdAt = entity.createdAt,
                    replyToMessageId = entity.replyToMessageId,
                    replyToAuthorType = wireAuthorType,
                    replyToPreview = entity.replyToPreview,
                    onProgress = { pct ->
                        if (pct in 1..99) {
                            scheduleThrottledDbProgress(msgId, pct)
                            val existing = transfersMap[msgId]
                            if (existing != null && existing.progress != pct) {
                                updateTransferEntry(existing.copy(progress = pct))
                            }
                        }
                    }
                )
            } else {
                FileSendResult.FAILED
            }
        } finally {
            removeTransferEntry(msgId)
        }

        val finalStatus = when (result) {
            FileSendResult.SENT -> DeliveryStatus.SENT.name
            FileSendResult.CANCELLED -> DeliveryStatus.CANCELLED.name
            FileSendResult.FAILED -> DeliveryStatus.FAILED.name
        }
        chatDao.updateTransferProgress(
            msgId,
            if (result == FileSendResult.SENT) 100 else 0,
            finalStatus
        )
        return result
    }

    suspend fun cancelTransfer(messageId: String) {
        val activeInfo = transfersMap[messageId]
        val msg = chatDao.getMessageById(messageId)
        val peerId = activeInfo?.peerId ?: msg?.peerId ?: return
        val isOutgoing = msg?.isOutgoing ?: activeInfo?.isOutgoing ?: true

        removeTransferEntry(messageId)
        nodeManager.cancelTransfer(peerId, messageId, isOutgoing)

        if (!isOutgoing) {
            mediaStorage.deleteMediaFile(msg?.mediaLocalPath)
        }
        chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
    }

    suspend fun retryTransfer(messageId: String): Boolean {
        val msg = chatDao.getMessageById(messageId) ?: return false
        nodeManager.clearTransferCancellation(messageId)
        if (msg.isOutgoing) {
            val file = msg.mediaLocalPath?.let { File(it) }
            if (file == null || !file.exists()) {
                onTransferUnavailableNotice?.invoke(msg.peerId, msg.messageId)
                chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.FAILED.name)
                return true
            }
            chatDao.updateTransferProgress(messageId, 1, DeliveryStatus.SENDING.name)
            return streamOutgoingMessage(msg, file) != FileSendResult.FAILED
        } else {
            chatDao.updateTransferProgress(messageId, 1, DeliveryStatus.SENDING.name)
            val connected = ensurePeerConnected(msg.peerId)
            val sentReq = connected && nodeManager.sendFileRetryRequest(msg.peerId, msg.messageId)
            if (!sentReq) {
                chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.FAILED.name)
            }
            return sentReq
        }
    }

    suspend fun markConversationRead(peerId: String) {
        if (!isAppInForegroundProvider()) return
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
        if (ensurePeerConnected(peerId)) {
            nodeManager.sendVerificationStatus(peerId, verified)
        }
    }

    override suspend fun isPeerVerifiedByMe(peerId: String): Boolean {
        return chatDao.getPeerById(peerId)?.isVerified == true
    }

    override suspend fun onPeerVerificationChanged(peerId: String, verifiedMe: Boolean) {
        chatDao.setPeerVerifiedMe(peerId, verifiedMe)
    }

    suspend fun deleteSingleMessage(peerId: String, messageId: String) {
        val msg = chatDao.getMessageById(messageId) ?: return
        mediaStorage.deleteMediaFile(msg.mediaLocalPath)
        chatDao.deleteMessageById(messageId)
        val latest = chatDao.getLatestMessageForPeer(peerId)
        val peer = chatDao.getPeerById(peerId)
        if (peer != null) {
            val nextPreview = when {
                latest == null -> ""
                latest.type == MessageType.IMAGE.name -> "Photo: ${latest.fileName ?: "image"}"
                latest.type == MessageType.VIDEO.name -> "Video: ${latest.fileName ?: "video"}"
                latest.type == MessageType.VOICE.name -> "Voice message (${NetworkUtils.formatDurationMs(latest.audioDurationMs)})"
                latest.type == MessageType.FILE.name -> "File: ${latest.fileName ?: "file"}"
                else -> latest.textContent
            }
            chatDao.updatePeerLastPreview(
                peerId = peerId,
                preview = nextPreview,
                lastActivityAt = latest?.createdAt ?: peer.lastActivityAt
            )
        }
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
            peerVerifiedMe = if (keyChanged) false else (existing?.peerVerifiedMe ?: false),
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
        val toRemove = transfersMap.values.filter { it.peerId == peerId && !it.isOutgoing }.map { it.messageId }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { msgId ->
                removeTransferEntry(msgId)
                val msg = chatDao.getMessageById(msgId)
                if (msg != null && msg.status == DeliveryStatus.SENDING.name) {
                    mediaStorage.deleteMediaFile(msg.mediaLocalPath)
                    chatDao.updateTransferProgress(msgId, 0, DeliveryStatus.FAILED.name)
                }
            }
        }
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
        _typingPeers.update { it - peerId }
        val isOpen = isAppInForegroundProvider() && currentlyOpenPeerId == peerId
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
            expiresAt = expiresAt,
            replyToMessageId = replyToMessageId,
            replyToSenderTag = replyToSenderTag,
            replyToPreview = replyToPreview
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
            onIncomingNotification?.invoke(peerId, senderName, text, peer?.avatarPath)
        }
    }

    override suspend fun onIncomingReaction(peerId: String, messageId: String, emoji: String) {
        val msg = chatDao.getMessageById(messageId) ?: return
        val nextJson = ReactionCodec.applyPeerReaction(msg.reactionsJson, emoji)
        chatDao.updateMessageReactions(messageId, nextJson)

        val isOpen = isAppInForegroundProvider() && currentlyOpenPeerId == peerId
        if (!isOpen && emoji.isNotBlank()) {
            val peer = chatDao.getPeerById(peerId)
            val senderName = peer?.username ?: "Chatso"
            val snippet = when (msg.type) {
                MessageType.IMAGE.name -> "Photo"
                MessageType.VIDEO.name -> "Video"
                MessageType.VOICE.name -> "Voice message"
                MessageType.FILE.name -> msg.fileName ?: "File"
                else -> msg.textContent
            }.trim().let { if (it.length > 48) "${it.take(45)}..." else it }
            onIncomingReactionNotification?.invoke(
                peerId,
                messageId,
                senderName,
                emoji,
                snippet,
                peer?.avatarPath
            )
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
        createdAt: Long,
        replyToMessageId: String?,
        replyToSenderTag: String?,
        replyToPreview: String?
    ): File {
        val existing = chatDao.getMessageById(messageId)
        val targetFile = mediaStorage.createIncomingMediaFile(messageId, fileName)
        val preview = when (messageType) {
            MessageType.IMAGE -> "Photo: $fileName"
            MessageType.VIDEO -> "Video: $fileName"
            MessageType.VOICE -> "Voice message"
            else -> "File: $fileName"
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
            createdAt = existing?.createdAt ?: createdAt,
            replyToMessageId = replyToMessageId,
            replyToSenderTag = replyToSenderTag,
            replyToPreview = replyToPreview,
            reactionsJson = existing?.reactionsJson ?: ""
        )
        chatDao.upsertMessage(msg)
        if (messageType != MessageType.VOICE || totalBytes > 64 * 1024) {
            updateTransferEntry(
                ActiveTransferInfo(
                    messageId = messageId,
                    peerId = peerId,
                    fileName = fileName,
                    progress = 1,
                    isOutgoing = false,
                    totalBytes = totalBytes
                )
            )
        }
        return targetFile
    }

    override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {
        scheduleThrottledDbProgress(messageId, progress)
        val existing = transfersMap[messageId]
        if (existing != null && existing.progress != progress) {
            updateTransferEntry(existing.copy(progress = progress))
        }
    }

    override suspend fun onIncomingFileCancelled(peerId: String, messageId: String) {
        removeTransferEntry(messageId)
        val msg = chatDao.getMessageById(messageId)
        mediaStorage.deleteMediaFile(msg?.mediaLocalPath)
        chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
    }

    override suspend fun onOutgoingFileCancelRequested(peerId: String, messageId: String) {
        removeTransferEntry(messageId)
        chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
    }

    override suspend fun onFileRetryRequested(peerId: String, messageId: String) {
        val msg = chatDao.getMessageById(messageId)
        val file = msg?.mediaLocalPath?.let { File(it) }
        if (msg == null || !msg.isOutgoing || file == null || !file.exists()) {
            nodeManager.sendFileUnavailable(peerId, messageId)
            return
        }
        scope.launch(Dispatchers.IO) {
            streamOutgoingMessage(msg, file)
        }
    }

    override suspend fun onFileRetryUnavailable(peerId: String, messageId: String) {
        removeTransferEntry(messageId)
        chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.FAILED.name)
        onTransferUnavailableNotice?.invoke(peerId, messageId)
    }

    override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {
        removeTransferEntry(messageId)
        val existing = chatDao.getMessageById(messageId) ?: return
        val isOpen = isAppInForegroundProvider() && currentlyOpenPeerId == peerId
        val now = System.currentTimeMillis()
        val expiresAt = if (isOpen && existing.selfDestructSeconds > 0) {
            now + existing.selfDestructSeconds * 1000L
        } else {
            null
        }
        val finalPreview = when (existing.type) {
            MessageType.IMAGE.name -> "Photo: ${existing.fileName ?: "image"}"
            MessageType.VIDEO.name -> "Video: ${existing.fileName ?: "video"}"
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
            onIncomingNotification?.invoke(peerId, senderName, finalPreview, peer?.avatarPath)
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
        syncTransferWakeLocks(false)
        discoveryManager.shutdown()
        nodeManager.disconnectAll()
    }
}
