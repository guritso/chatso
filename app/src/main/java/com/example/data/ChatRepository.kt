package com.example.data

import android.content.Context
import android.net.Uri
import com.example.R
import com.example.crypto.CryptoEngine
import com.example.media.VideoCompressor
import com.example.network.DiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.P2PEventListener
import com.example.network.P2PNodeManager
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val mediaStorage: MediaStorageHelper,
    private val isAppInForegroundProvider: () -> Boolean = { true }
) : P2PEventListener {

    private val appContext = context.applicationContext
    val discoveryManager = DiscoveryManager(appContext, scope)
    val nodeManager = P2PNodeManager(scope, discoveryManager, this)

    val allPeersFlow: Flow<List<PeerEntity>> = chatDao.observeAllPeers()

    companion object {
        const val DEV_FAKE_PEER_PREFIX = "dev_fake_"
    }

    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()

    private val _devOnlineFakePeers = MutableStateFlow<Set<String>>(emptySet())
    val devOnlineFakePeers: StateFlow<Set<String>> = _devOnlineFakePeers.asStateFlow()

    private val _devAutoReplyEnabled = MutableStateFlow(true)
    val devAutoReplyEnabled: StateFlow<Boolean> = _devAutoReplyEnabled.asStateFlow()

    private val _unreadDivider = MutableStateFlow<UnreadDividerInfo?>(null)
    val unreadDivider: StateFlow<UnreadDividerInfo?> = _unreadDivider.asStateFlow()

    val totalMessageCountFlow: Flow<Int> = chatDao.observeTotalMessageCount()

    private val transfersMap = ConcurrentHashMap<String, ActiveTransferInfo>()
    private val _activeTransfers = MutableStateFlow<List<ActiveTransferInfo>>(emptyList())
    val activeTransfers: StateFlow<List<ActiveTransferInfo>> = _activeTransfers.asStateFlow()

    private val outgoingTransferJobs = ConcurrentHashMap<String, Job>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()

    private val _transferAlertResIds = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val transferAlertResIds: SharedFlow<Int> = _transferAlertResIds.asSharedFlow()

    @Volatile
    var currentlyOpenPeerId: String? = null

    var onClearPeerNotification: ((peerId: String) -> Unit)? = null

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

    private fun publishTransfersSnapshot() {
        _activeTransfers.value = transfersMap.values.sortedBy { it.fileName }
    }

    private fun updateTransferEntry(info: ActiveTransferInfo) {
        transfersMap[info.messageId] = info
        publishTransfersSnapshot()
    }

    private fun removeTransferEntry(messageId: String) {
        if (transfersMap.remove(messageId) != null) {
            publishTransfersSnapshot()
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
                captureUnreadDividerAndMarkRead(openPeer, preserveExistingAnchor = true)
            }
        }
    }

    suspend fun captureUnreadDividerAndMarkRead(
        peerId: String,
        preserveExistingAnchor: Boolean = false
    ) {
        if (isAppInForegroundProvider()) {
            val unread = chatDao.getUnreadIncomingMessagesChronological(peerId)
                .filter { it.transferProgress >= 100 }
            if (unread.isNotEmpty()) {
                val current = _unreadDivider.value
                if (preserveExistingAnchor && current != null && current.peerId == peerId) {
                    val anchorStillExists = chatDao.getMessageById(current.anchorMessageId) != null
                    if (anchorStillExists) {
                        _unreadDivider.value = current.copy(
                            unreadCount = current.unreadCount + unread.size
                        )
                    } else {
                        _unreadDivider.value = UnreadDividerInfo(
                            peerId = peerId,
                            anchorMessageId = unread.first().messageId,
                            unreadCount = unread.size
                        )
                    }
                } else {
                    _unreadDivider.value = UnreadDividerInfo(
                        peerId = peerId,
                        anchorMessageId = unread.first().messageId,
                        unreadCount = unread.size
                    )
                }
            } else if (!preserveExistingAnchor) {
                _unreadDivider.value = null
            }
        }
        markConversationRead(peerId)
    }

    fun clearUnreadDivider() {
        _unreadDivider.value = null
    }

    suspend fun connectDirect(
        ip: String,
        port: Int,
        expectedPubKeyBase64: String = "",
        inviteToken: String = ""
    ): Result<String> {
        return nodeManager.connectToPeer(ip, port, expectedPubKeyBase64, inviteToken)
    }

    fun isDevSimulatedOnline(peerId: String): Boolean =
        peerId.startsWith(DEV_FAKE_PEER_PREFIX) && _devOnlineFakePeers.value.contains(peerId)

    suspend fun ensurePeerConnected(peerId: String): Boolean {
        if (isDevSimulatedOnline(peerId)) return true
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
            MessageType.STICKER.name -> "Sticker"
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

        if (isDevSimulatedOnline(peerId)) {
            chatDao.updateMessageStatus(msgId, DeliveryStatus.DELIVERED.name)
            scope.launch(Dispatchers.IO) {
                delay(450L)
                onDeliveryReceipt(peerId, msgId, DeliveryStatus.READ)
                if (_devAutoReplyEnabled.value) {
                    _typingPeers.update { it + peerId }
                    delay(1100L)
                    val replyMsg = "[Auto-reply] $text"
                    devSimulateIncomingText(peerId, replyMsg)
                }
            }
            return true
        }

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

    suspend fun sendStickerStream(
        peerId: String,
        inputStream: InputStream,
        mimeType: String,
        fileNameHint: String? = null,
        replyTo: MessageEntity? = null
    ): Boolean {
        val copied = mediaStorage.copyStreamToPrivateMedia(
            inputStream = inputStream,
            mimeType = mimeType,
            fileNameHint = fileNameHint,
            fallbackPrefix = "sticker"
        ) ?: return false
        return sendPreparedFile(
            peerId = peerId,
            messageType = MessageType.STICKER,
            file = copied.localFile,
            fileName = copied.fileName,
            mimeType = copied.mimeType,
            audioDurationMs = 0L,
            replyTo = replyTo
        )
    }

    fun observeFavoriteStickers(): Flow<List<FavoriteStickerEntity>> =
        chatDao.observeFavoriteStickers()

    suspend fun isMessageStickerFavorited(message: MessageEntity): Boolean {
        if (message.type != MessageType.STICKER.name) return false
        val path = message.mediaLocalPath ?: return false
        val file = File(path)
        if (!file.exists()) return false
        val sha256 = mediaStorage.computeFileSha256(file) ?: return false
        return chatDao.getFavoriteStickerByHash(sha256) != null
    }

    suspend fun toggleFavoriteSticker(message: MessageEntity): Boolean? {
        if (message.type != MessageType.STICKER.name) return null
        val path = message.mediaLocalPath ?: return null
        val sourceFile = File(path)
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return null
        val sha256 = mediaStorage.computeFileSha256(sourceFile) ?: return null
        val existing = chatDao.getFavoriteStickerByHash(sha256)
        return if (existing != null) {
            chatDao.deleteFavoriteSticker(existing.stickerId)
            mediaStorage.deleteMediaFile(existing.localPath)
            false
        } else {
            val mime = message.mimeType?.takeIf { it.isNotBlank() } ?: "image/webp"
            val savedFile = mediaStorage.saveFavoriteStickerCopy(sourceFile, mime, sha256)
                ?: return null
            chatDao.upsertFavoriteSticker(
                FavoriteStickerEntity(
                    stickerId = UUID.randomUUID().toString(),
                    localPath = savedFile.absolutePath,
                    mimeType = mime,
                    sha256 = sha256,
                    createdAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun removeFavoriteSticker(stickerId: String) {
        val existing = chatDao.getFavoriteStickerById(stickerId) ?: return
        chatDao.deleteFavoriteSticker(stickerId)
        mediaStorage.deleteMediaFile(existing.localPath)
    }

    suspend fun sendFavoriteSticker(
        peerId: String,
        sticker: FavoriteStickerEntity,
        replyTo: MessageEntity? = null
    ): Boolean {
        val sourceFile = File(sticker.localPath)
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        val duplicated = mediaStorage.duplicateFileToPrivateMedia(
            sourceFile = sourceFile,
            prefix = "sticker",
            extHint = sourceFile.extension.ifBlank { "webp" }
        ) ?: return false
        return sendPreparedFile(
            peerId = peerId,
            messageType = MessageType.STICKER,
            file = duplicated,
            fileName = duplicated.name,
            mimeType = sticker.mimeType.ifBlank { "image/webp" },
            audioDurationMs = 0L,
            replyTo = replyTo
        )
    }

    suspend fun sendLocalSavedFile(
        peerId: String,
        sourceFile: File,
        messageType: MessageType,
        mimeType: String,
        displayName: String,
        replyTo: MessageEntity? = null
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        val prefix = when (messageType) {
            MessageType.IMAGE -> "photo"
            MessageType.VIDEO -> "video"
            MessageType.STICKER -> "sticker"
            else -> "file"
        }
        val duplicated = mediaStorage.duplicateFileToPrivateMedia(
            sourceFile = sourceFile,
            prefix = prefix,
            extHint = sourceFile.extension.ifBlank { null }
        ) ?: return false
        val durMs = if (messageType == MessageType.VIDEO) {
            mediaStorage.readAudioDurationMs(duplicated)
        } else {
            0L
        }
        return sendPreparedFile(
            peerId = peerId,
            messageType = messageType,
            file = duplicated,
            fileName = displayName.ifBlank { duplicated.name },
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            audioDurationMs = durMs,
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
            MessageType.STICKER -> "Sticker"
            MessageType.VOICE -> "Voice message (${NetworkUtils.formatDurationMs(audioDurationMs)})"
            else -> "File: $fileName"
        }
        val (replyId, combinedTag, replyPreview) = buildReplyMeta(replyTo)
        val localSenderTag = combinedTag?.substringBefore('|')
        val wireAuthorType = combinedTag?.substringAfter('|')
        val totalBytes = file.length()

        var activeEntity = MessageEntity(
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
        chatDao.upsertMessage(activeEntity)
        chatDao.upsertPeer(
            peer.copy(
                lastMessagePreview = preview,
                lastActivityAt = now
            )
        )

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

        val currentJob = coroutineContext[Job]
        if (currentJob != null) {
            outgoingTransferJobs[msgId] = currentJob
        }

        var compressedCandidate: File? = null
        val ok = try {
            var fileToSend = file
            var sendFileName = fileName
            var sendMimeType = mimeType
            var sendDurationMs = audioDurationMs
            var sendTotalBytes = totalBytes
            var compressedUsed = false

            if (messageType == MessageType.VIDEO) {
                val candidate = mediaStorage.createCompressedVideoFile(fileName)
                compressedCandidate = candidate
                val didCompress = VideoCompressor.compressIfNeeded(
                    context = appContext,
                    inputFile = file,
                    outputFile = candidate,
                    onProgress = { pct ->
                        if (cancelledTransferIds.contains(msgId)) return@compressIfNeeded
                        val mapped = (1 + (pct * 34) / 100).coerceIn(1, 35)
                        scope.launch(Dispatchers.IO) {
                            if (cancelledTransferIds.contains(msgId)) return@launch
                            chatDao.updateTransferProgress(msgId, mapped, DeliveryStatus.SENDING.name)
                            if (transfersMap.containsKey(msgId)) {
                                updateTransferEntry(
                                    ActiveTransferInfo(
                                        messageId = msgId,
                                        peerId = peerId,
                                        fileName = sendFileName,
                                        progress = mapped,
                                        isOutgoing = true,
                                        totalBytes = sendTotalBytes
                                    )
                                )
                            }
                        }
                    }
                )
                if (cancelledTransferIds.contains(msgId)) {
                    runCatching { if (candidate.exists()) candidate.delete() }
                    return false
                }
                if (didCompress) {
                    compressedUsed = true
                    mediaStorage.deleteMediaFile(file.absolutePath)
                    fileToSend = candidate
                    val baseName = fileName.substringBeforeLast('.').ifBlank { "video" }
                    sendFileName = "$baseName.mp4"
                    sendMimeType = "video/mp4"
                    sendTotalBytes = candidate.length()
                    if (sendDurationMs <= 0L) {
                        sendDurationMs = mediaStorage.readAudioDurationMs(candidate)
                    }
                    val updatedPreview = "Video: $sendFileName"
                    activeEntity = activeEntity.copy(
                        textContent = updatedPreview,
                        mediaLocalPath = fileToSend.absolutePath,
                        fileName = sendFileName,
                        mimeType = sendMimeType,
                        fileSizeBytes = sendTotalBytes,
                        audioDurationMs = sendDurationMs,
                        transferProgress = 35
                    )
                    chatDao.upsertMessage(activeEntity)
                    chatDao.updatePeerLastPreview(peerId, updatedPreview, now)
                    updateTransferEntry(
                        ActiveTransferInfo(
                            messageId = msgId,
                            peerId = peerId,
                            fileName = sendFileName,
                            progress = 35,
                            isOutgoing = true,
                            totalBytes = sendTotalBytes
                        )
                    )
                }
            }

            if (cancelledTransferIds.contains(msgId)) {
                false
            } else if (isDevSimulatedOnline(peerId)) {
                chatDao.updateTransferProgress(msgId, 100, DeliveryStatus.DELIVERED.name)
                true
            } else {
                val connected = ensurePeerConnected(peerId)
                if (connected && !cancelledTransferIds.contains(msgId)) {
                    nodeManager.sendMediaOrFile(
                        peerId = peerId,
                        messageId = msgId,
                        messageType = messageType,
                        file = fileToSend,
                        fileName = sendFileName,
                        mimeType = sendMimeType,
                        audioDurationMs = sendDurationMs,
                        selfDestructSec = sdSec,
                        createdAt = now,
                        replyToMessageId = replyId,
                        replyToAuthorType = wireAuthorType,
                        replyToPreview = replyPreview,
                        isCancelled = { cancelledTransferIds.contains(msgId) },
                        onProgress = { pct ->
                            if (cancelledTransferIds.contains(msgId)) return@sendMediaOrFile
                            val mappedPct = if (compressedUsed && pct < 100) {
                                (35 + (pct * 64) / 100).coerceIn(35, 99)
                            } else {
                                pct
                            }
                            chatDao.updateTransferProgress(msgId, mappedPct, DeliveryStatus.SENDING.name)
                            if (mappedPct in 1..99 && transfersMap.containsKey(msgId)) {
                                updateTransferEntry(
                                    ActiveTransferInfo(
                                        messageId = msgId,
                                        peerId = peerId,
                                        fileName = sendFileName,
                                        progress = mappedPct,
                                        isOutgoing = true,
                                        totalBytes = sendTotalBytes
                                    )
                                )
                            }
                        }
                    )
                } else {
                    false
                }
            }
        } catch (_: CancellationException) {
            compressedCandidate?.let { runCatching { if (it.exists()) it.delete() } }
            false
        } finally {
            if (currentJob != null) {
                outgoingTransferJobs.remove(msgId, currentJob)
            } else {
                outgoingTransferJobs.remove(msgId)
            }
            removeTransferEntry(msgId)
        }

        if (!cancelledTransferIds.remove(msgId)) {
            chatDao.updateTransferProgress(
                msgId,
                if (ok) 100 else 0,
                if (ok) DeliveryStatus.SENT.name else DeliveryStatus.FAILED.name
            )
        }
        return ok
    }

    suspend fun cancelTransfer(messageId: String) {
        val msg = chatDao.getMessageById(messageId)
        cancelledTransferIds.add(messageId)
        removeTransferEntry(messageId)
        if (msg == null) {
            outgoingTransferJobs.remove(messageId)?.cancel()
            return
        }
        if (msg.isOutgoing) {
            outgoingTransferJobs.remove(messageId)?.cancel()
            nodeManager.sendFileCancel(msg.peerId, messageId, bySender = true)
            deleteSingleMessage(msg.peerId, messageId)
        } else {
            nodeManager.cancelIncomingTransfer(msg.peerId, messageId)
            nodeManager.sendFileCancel(msg.peerId, messageId, bySender = false)
            mediaStorage.deleteMediaFile(msg.mediaLocalPath)
            chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
        }
    }

    suspend fun retryIncomingDownload(messageId: String): Boolean {
        val msg = chatDao.getMessageById(messageId) ?: return false
        if (msg.isOutgoing) return false
        cancelledTransferIds.remove(messageId)
        val connected = ensurePeerConnected(msg.peerId)
        if (!connected) {
            _transferAlertResIds.tryEmit(R.string.msg_peer_offline)
            return false
        }
        chatDao.updateTransferProgress(messageId, 1, DeliveryStatus.SENDING.name)
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = messageId,
                peerId = msg.peerId,
                fileName = msg.fileName ?: "file",
                progress = 1,
                isOutgoing = false,
                totalBytes = msg.fileSizeBytes
            )
        )
        val requested = nodeManager.sendFileRetryRequest(msg.peerId, messageId)
        if (!requested) {
            removeTransferEntry(messageId)
            chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
        }
        return requested
    }

    suspend fun markConversationRead(peerId: String, fromNotification: Boolean = false) {
        if (!fromNotification && !isAppInForegroundProvider()) return
        chatDao.clearUnread(peerId)
        onClearPeerNotification?.invoke(peerId)
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
                latest.type == MessageType.STICKER.name -> "Sticker"
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
        if (_unreadDivider.value?.peerId == peerId) {
            _unreadDivider.value = null
        }
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
            toRemove.forEach { transfersMap.remove(it) }
            publishTransfersSnapshot()
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
                MessageType.STICKER.name -> "Sticker"
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
        cancelledTransferIds.remove(messageId)
        val targetFile = mediaStorage.createIncomingMediaFile(messageId, fileName)
        val preview = when (messageType) {
            MessageType.IMAGE -> "Receiving photo: $fileName"
            MessageType.VIDEO -> "Receiving video: $fileName"
            MessageType.STICKER -> "Receiving sticker…"
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
            createdAt = createdAt,
            replyToMessageId = replyToMessageId,
            replyToSenderTag = replyToSenderTag,
            replyToPreview = replyToPreview
        )
        chatDao.upsertMessage(msg)
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
        return targetFile
    }

    override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {
        if (cancelledTransferIds.contains(messageId)) return
        chatDao.updateTransferProgress(messageId, progress, DeliveryStatus.SENDING.name)
        val existing = transfersMap[messageId]
        if (existing != null) {
            updateTransferEntry(existing.copy(progress = progress))
        }
    }

    override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {
        removeTransferEntry(messageId)
        if (cancelledTransferIds.remove(messageId)) {
            mediaStorage.deleteMediaFile(file.absolutePath)
            return
        }
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
            MessageType.STICKER.name -> "Sticker"
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

    override suspend fun onIncomingFileCancelledBySender(peerId: String, messageId: String) {
        cancelledTransferIds.add(messageId)
        removeTransferEntry(messageId)
        deleteSingleMessage(peerId, messageId)
    }

    override suspend fun onOutgoingFileCancelledByReceiver(peerId: String, messageId: String) {
        cancelledTransferIds.add(messageId)
        outgoingTransferJobs.remove(messageId)?.cancel()
        removeTransferEntry(messageId)
        val existing = chatDao.getMessageById(messageId) ?: return
        chatDao.updateTransferProgress(messageId, existing.transferProgress, DeliveryStatus.CANCELLED.name)
    }

    override suspend fun onPeerRequestedFileRetry(peerId: String, messageId: String) {
        val msg = chatDao.getMessageById(messageId)
        val localFile = msg?.mediaLocalPath?.let { File(it) }
        if (msg == null || !msg.isOutgoing || localFile == null || !localFile.exists()) {
            nodeManager.sendFileUnavailable(peerId, messageId)
            return
        }
        cancelledTransferIds.remove(messageId)
        val msgType = MessageType.entries.firstOrNull { it.name == msg.type } ?: MessageType.FILE
        val fileName = msg.fileName ?: localFile.name
        val mimeType = msg.mimeType ?: "application/octet-stream"
        val totalBytes = localFile.length()

        val retryJob = coroutineContext[Job]
        if (retryJob != null) {
            outgoingTransferJobs[messageId] = retryJob
        }
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = messageId,
                peerId = peerId,
                fileName = fileName,
                progress = 1,
                isOutgoing = true,
                totalBytes = totalBytes
            )
        )
        val ok = try {
            nodeManager.sendMediaOrFile(
                peerId = peerId,
                messageId = messageId,
                messageType = msgType,
                file = localFile,
                fileName = fileName,
                mimeType = mimeType,
                audioDurationMs = msg.audioDurationMs,
                selfDestructSec = msg.selfDestructSeconds,
                createdAt = msg.createdAt,
                replyToMessageId = msg.replyToMessageId,
                replyToAuthorType = when (msg.replyToSenderTag) {
                    ReplySenderTags.ME -> "ME"
                    ReplySenderTags.PEER -> "PEER"
                    else -> null
                },
                replyToPreview = msg.replyToPreview,
                isCancelled = { cancelledTransferIds.contains(messageId) },
                onProgress = { pct ->
                    if (cancelledTransferIds.contains(messageId)) return@sendMediaOrFile
                    if (pct in 1..99 && transfersMap.containsKey(messageId)) {
                        updateTransferEntry(
                            ActiveTransferInfo(
                                messageId = messageId,
                                peerId = peerId,
                                fileName = fileName,
                                progress = pct,
                                isOutgoing = true,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
            )
        } finally {
            if (retryJob != null) {
                outgoingTransferJobs.remove(messageId, retryJob)
            } else {
                outgoingTransferJobs.remove(messageId)
            }
            removeTransferEntry(messageId)
        }
        if (!cancelledTransferIds.remove(messageId) && ok) {
            chatDao.updateTransferProgress(messageId, 100, DeliveryStatus.SENT.name)
        }
    }

    override suspend fun onRemoteFileUnavailable(peerId: String, messageId: String) {
        removeTransferEntry(messageId)
        chatDao.updateTransferProgress(messageId, 0, DeliveryStatus.CANCELLED.name)
        _transferAlertResIds.tryEmit(R.string.msg_file_unavailable)
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

    // --- Developer Mode Simulation Helpers ---

    suspend fun devCreateFakePeer(
        usernameInput: String,
        selfDestructSeconds: Int = 0
    ): PeerEntity {
        val sampleNames = listOf(
            "Alice Dev",
            "Bob Local",
            "Clara Test",
            "Diego P2P",
            "Elena Sandbox",
            "Rafael QA"
        )
        val existingCount = chatDao.getAllPeersOnce().count { it.peerId.startsWith(DEV_FAKE_PEER_PREFIX) }
        val resolvedName = usernameInput.trim().ifBlank {
            val base = sampleNames[existingCount % sampleNames.size]
            if (existingCount >= sampleNames.size) "$base #${existingCount + 1}" else base
        }
        val mat = CryptoEngine.createIdentityMaterial()
        val fakePeerId = "$DEV_FAKE_PEER_PREFIX${mat.peerId}"
        onPeerHandshaked(
            peerId = fakePeerId,
            publicKeyBase64 = mat.publicKeyBase64,
            remoteIp = "127.0.0.1",
            remoteListenPort = LocalIdentity.DEFAULT_TCP_PORT + 10 + (existingCount % 50),
            username = resolvedName,
            avatarBase64 = null
        )
        if (selfDestructSeconds > 0) {
            chatDao.updatePeerSelfDestruct(fakePeerId, selfDestructSeconds)
        }
        _devOnlineFakePeers.update { it + fakePeerId }
        return chatDao.getPeerById(fakePeerId)!!
    }

    suspend fun devSimulateIncomingText(
        peerId: String,
        rawText: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (chatDao.getPeerById(peerId) == null) return false
        val text = rawText.trim().ifBlank { "Simulated P2P test message" }
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = "sim_${UUID.randomUUID()}"
        onIncomingTextMessage(
            peerId = peerId,
            messageId = msgId,
            text = text,
            selfDestructSec = peer.selfDestructSeconds,
            createdAt = createdAt,
            replyToMessageId = null,
            replyToSenderTag = null,
            replyToPreview = null
        )
        return true
    }

    suspend fun devSimulateIncomingMedia(peerId: String, asSticker: Boolean): Boolean {
        val peer = chatDao.getPeerById(peerId) ?: return false
        val msgId = "sim_media_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val ext = if (asSticker) "png" else "jpg"
        val fileName = if (asSticker) "dev_sticker_${now % 10000}.$ext" else "dev_photo_${now % 10000}.$ext"
        val mime = if (asSticker) "image/png" else "image/jpeg"
        val messageType = if (asSticker) MessageType.STICKER else MessageType.IMAGE

        val tempFile = File(appContext.cacheDir, fileName)
        val bmp = if (asSticker) {
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

        try {
            tempFile.outputStream().use { out ->
                val format = if (asSticker) {
                    android.graphics.Bitmap.CompressFormat.PNG
                } else {
                    android.graphics.Bitmap.CompressFormat.JPEG
                }
                bmp.compress(format, 92, out)
            }
        } finally {
            bmp.recycle()
        }

        val targetFile = onIncomingFileStart(
            peerId = peerId,
            messageId = msgId,
            messageType = messageType,
            fileName = fileName,
            mimeType = mime,
            totalBytes = tempFile.length(),
            audioDurationMs = 0L,
            selfDestructSec = peer.selfDestructSeconds,
            createdAt = now,
            replyToMessageId = null,
            replyToSenderTag = null,
            replyToPreview = null
        )
        tempFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.delete()
        onIncomingFileComplete(peerId, msgId, targetFile)
        return true
    }

    suspend fun devSimulateTyping(peerId: String, durationMs: Long = 4000L) {
        onTypingChanged(peerId, true)
        scope.launch(Dispatchers.IO) {
            delay(durationMs)
            onTypingChanged(peerId, false)
        }
    }

    suspend fun devSimulateIncomingReaction(peerId: String, emoji: String): Boolean {
        val latest = chatDao.getLatestMessageForPeer(peerId) ?: return false
        onIncomingReaction(peerId, latest.messageId, emoji)
        return true
    }

    suspend fun devTriggerKeyChangeWarning(peerId: String) {
        chatDao.setPeerKeyChangedWarning(peerId, true)
    }

    fun devSetPeerSimulatedOnline(peerId: String, online: Boolean) {
        _devOnlineFakePeers.update { current ->
            if (online) current + peerId else current - peerId
        }
    }

    fun devSetAutoReplyEnabled(enabled: Boolean) {
        _devAutoReplyEnabled.value = enabled
    }

    suspend fun devClearFakePeers(): Int {
        val allPeers = chatDao.getAllPeersOnce()
        val fakes = allPeers.filter { it.peerId.startsWith(DEV_FAKE_PEER_PREFIX) }
        for (peer in fakes) {
            onClearPeerNotification?.invoke(peer.peerId)
            deleteConversation(peer.peerId)
            _devOnlineFakePeers.update { it - peer.peerId }
            _typingPeers.update { it - peer.peerId }
        }
        return fakes.size
    }

    fun shutdown() {
        discoveryManager.shutdown()
        nodeManager.disconnectAll()
    }
}
