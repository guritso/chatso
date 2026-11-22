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
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    val bridgeTelemetry: StateFlow<BridgeTelemetry> = nodeManager.bridgeTelemetry

    val totalMessageCountFlow: Flow<Int> = chatDao.observeTotalMessageCount()

    private val transfersMap = ConcurrentHashMap<String, ActiveTransferInfo>()
    private val _activeTransfers = MutableStateFlow<List<ActiveTransferInfo>>(emptyList())
    val activeTransfers: StateFlow<List<ActiveTransferInfo>> = _activeTransfers.asStateFlow()

    private val outgoingTransferJobs = ConcurrentHashMap<String, Job>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val outboxMutexByPeer = ConcurrentHashMap<String, Mutex>()
    private val sendingInFlightIds = ConcurrentHashMap.newKeySet<String>()
    private val incomingTransferStartTimes = ConcurrentHashMap<String, Long>()
    private val transientTestPeerIds = ConcurrentHashMap.newKeySet<String>()
    private val connectMutexByPeer = ConcurrentHashMap<String, Mutex>()
    private val lastAutoConnectAttemptMs = ConcurrentHashMap<String, Long>()
    private val deletingPeerIds = ConcurrentHashMap.newKeySet<String>()
    private val deleteGenerationByPeer = ConcurrentHashMap<String, AtomicLong>()
    private val dismissedPrefs = appContext.getSharedPreferences("chatso_dismissed_peers", Context.MODE_PRIVATE)
    private val dismissedPeerIds = ConcurrentHashMap.newKeySet<String>().apply {
        dismissedPrefs.getStringSet("ids", emptySet())?.let { addAll(it) }
    }
    private var autoConnectJob: Job? = null

    private val _transferAlertResIds = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val transferAlertResIds: SharedFlow<Int> = _transferAlertResIds.asSharedFlow()

    @Volatile
    var currentlyOpenPeerId: String? = null

    @Volatile
    var isOpenChatAtBottom: Boolean = true

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
            var wasInit = false
            var wasBridge = false
            identityStore.identityFlow.collectLatest { identity ->
                nodeManager.updateIdentityAndEnsureServer(identity)
                if (identity.isInitialized && (!wasInit || (!wasBridge && identity.bridgeEnabled))) {
                    launch {
                        delay(300L)
                        autoConnectActiveChats(force = true)
                    }
                }
                wasInit = identity.isInitialized
                wasBridge = identity.bridgeEnabled
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                cleanupExpiredMessages()
                delay(1000L)
            }
        }
        autoConnectJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(12_000L)
                if (identityStore.getSnapshot().isInitialized && nodeManager.serverRunning.value) {
                    autoConnectActiveChats(force = false)
                }
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

    private fun computeEtaSeconds(startedAtMs: Long, progressPct: Int): Long? {
        if (progressPct < 3 || progressPct >= 100) return null
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        if (elapsedMs < 800L) return null
        val remainingMs = (elapsedMs.toDouble() * (100 - progressPct)) / progressPct.toDouble()
        val secs = kotlin.math.ceil(remainingMs / 1000.0).toLong()
        return secs.coerceIn(1L, 86_400L)
    }

    fun observePeer(peerId: String): Flow<PeerEntity?> = chatDao.observePeer(peerId)

    fun observeMessages(peerId: String, limit: Int): Flow<List<MessageEntity>> =
        chatDao.observeMessagesForPeer(peerId, limit)

    fun onAppForegroundChanged(inForeground: Boolean) {
        nodeManager.setLocalAppInForeground(inForeground)
        if (inForeground) {
            scope.launch(Dispatchers.IO) {
                autoConnectActiveChats(force = true)
                val openPeer = currentlyOpenPeerId
                if (openPeer != null) {
                    prepareConversationOpen(openPeer, preserveExistingAnchor = true)
                }
            }
        }
    }

    fun setOpenChatAtBottom(peerId: String, atBottom: Boolean) {
        if (currentlyOpenPeerId == peerId) {
            isOpenChatAtBottom = atBottom
        }
    }

    suspend fun prepareConversationOpen(
        peerId: String,
        preserveExistingAnchor: Boolean = false
    ) {
        if (!isAppInForegroundProvider()) return
        onClearPeerNotification?.invoke(peerId)
        val unread = chatDao.getUnreadIncomingMessagesChronological(peerId)
            .filter { it.transferProgress >= 100 }
        if (unread.isNotEmpty()) {
            val current = _unreadDivider.value
            val existingAnchorMsg = if (preserveExistingAnchor && current != null && current.peerId == peerId) {
                chatDao.getMessageById(current.anchorMessageId)
            } else {
                null
            }
            if (existingAnchorMsg != null && existingAnchorMsg.status != DeliveryStatus.READ.name) {
                _unreadDivider.value = current!!.copy(unreadCount = unread.size)
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

    suspend fun markVisibleMessagesRead(peerId: String, visibleMessageIds: Set<String>) {
        if (!isAppInForegroundProvider() || visibleMessageIds.isEmpty()) return
        val unread = chatDao.getUnreadIncomingMessages(peerId)
            .filter { it.transferProgress >= 100 }
        if (unread.isEmpty()) return

        val now = System.currentTimeMillis()
        var markedCount = 0
        for (msg in unread) {
            if (!visibleMessageIds.contains(msg.messageId)) continue
            val expiresAt = if (msg.selfDestructSeconds > 0) {
                now + msg.selfDestructSeconds * 1000L
            } else {
                null
            }
            chatDao.markMessageRead(msg.messageId, DeliveryStatus.READ.name, now, expiresAt)
            nodeManager.sendReadReceipt(peerId, msg.messageId)
            markedCount++
        }
        if (markedCount > 0) {
            val remaining = (unread.size - markedCount).coerceAtLeast(0)
            chatDao.setUnreadCount(peerId, remaining)
            if (remaining == 0) {
                onClearPeerNotification?.invoke(peerId)
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

    suspend fun connectViaBridge(
        bridgePeerId: String,
        targetPeerId: String,
        expectedPubKeyBase64: String = "",
        inviteToken: String = ""
    ): Result<String> {
        return nodeManager.connectViaBridge(bridgePeerId, targetPeerId, expectedPubKeyBase64, inviteToken)
    }

    fun isDevSimulatedOnline(peerId: String): Boolean =
        peerId.startsWith(DEV_FAKE_PEER_PREFIX) && _devOnlineFakePeers.value.contains(peerId)

    suspend fun ensurePeerConnected(peerId: String, isAutoReconnect: Boolean = false): Boolean {
        if (deletingPeerIds.contains(peerId)) return false
        if (isDevSimulatedOnline(peerId)) return true
        if (nodeManager.connectedPeers.value.contains(peerId)) return true
        val mutex = connectMutexByPeer.computeIfAbsent(peerId) { Mutex() }
        return mutex.withLock {
            if (deletingPeerIds.contains(peerId)) return@withLock false
            if (isDevSimulatedOnline(peerId)) return@withLock true
            if (nodeManager.connectedPeers.value.contains(peerId)) return@withLock true

            val startGen = deleteGenerationByPeer[peerId]?.get() ?: 0L
            val peer = chatDao.getPeerById(peerId)
            if (isAutoReconnect && peer == null) return@withLock false

            suspend fun finalizeAttempt(connected: Boolean): Boolean {
                if (!connected) return false
                val endGen = deleteGenerationByPeer[peerId]?.get() ?: 0L
                if (endGen != startGen ||
                    deletingPeerIds.contains(peerId) ||
                    (isAutoReconnect && chatDao.getPeerById(peerId) == null)
                ) {
                    nodeManager.disconnectPeer(peerId)
                    chatDao.deleteAllMessagesForPeer(peerId)
                    chatDao.deletePeer(peerId)
                    return false
                }
                return true
            }

            val discoveredDirect = discoveryManager.discoveredPeers.value[peerId]
                ?.takeIf { it.viaBridgePeerId.isNullOrBlank() }
            val discoveredBridged = discoveryManager.discoveredPeers.value[peerId]
                ?.takeIf { !it.viaBridgePeerId.isNullOrBlank() }

            if (discoveredDirect != null) {
                if (nodeManager.connectToPeer(
                        host = discoveredDirect.ipAddress,
                        port = discoveredDirect.port,
                        expectedPubKeyBase64 = discoveredDirect.publicKeyBase64.ifBlank { peer?.publicKeyBase64.orEmpty() },
                        isAutoReconnect = isAutoReconnect
                    ).isSuccess
                ) {
                    return@withLock finalizeAttempt(true)
                }
            }

            if (peer != null && peer.lastIp.isNotBlank() && peer.lastPort in 1..65535 && peer.viaBridgePeerId.isBlank() && discoveredBridged == null) {
                val sameAsDiscovered = discoveredDirect != null &&
                    NetworkUtils.normalizeHost(discoveredDirect.ipAddress) == NetworkUtils.normalizeHost(peer.lastIp) &&
                    discoveredDirect.port == peer.lastPort
                if (!sameAsDiscovered && nodeManager.connectToPeer(
                        host = peer.lastIp,
                        port = peer.lastPort,
                        expectedPubKeyBase64 = peer.publicKeyBase64,
                        isAutoReconnect = isAutoReconnect
                    ).isSuccess
                ) {
                    return@withLock finalizeAttempt(true)
                }
                if (!NetworkUtils.isLoopbackHost(peer.lastIp) &&
                    peer.publicKeyBase64.isNotBlank() &&
                    nodeManager.connectToPeer(
                        host = "127.0.0.1",
                        port = peer.lastPort,
                        expectedPubKeyBase64 = peer.publicKeyBase64,
                        isAutoReconnect = isAutoReconnect
                    ).isSuccess
                ) {
                    return@withLock finalizeAttempt(true)
                }
            }

            val bridgeId = discoveredBridged?.viaBridgePeerId
                ?: peer?.viaBridgePeerId?.takeIf { it.isNotBlank() }
            val targetPub = discoveredBridged?.publicKeyBase64 ?: peer?.publicKeyBase64.orEmpty()
            if (!bridgeId.isNullOrBlank()) {
                if (!nodeManager.connectedPeers.value.contains(bridgeId)) {
                    val bridgePeer = chatDao.getPeerById(bridgeId)
                    val bridgeIp = discoveredBridged?.bridgeHost ?: bridgePeer?.lastIp.orEmpty()
                    val bridgePort = discoveredBridged?.bridgePort ?: bridgePeer?.lastPort ?: LocalIdentity.DEFAULT_TCP_PORT
                    val bridgePub = bridgePeer?.publicKeyBase64.orEmpty()
                    if (bridgeIp.isNotBlank() && bridgePort in 1..65535) {
                        nodeManager.connectToPeer(
                            host = bridgeIp,
                            port = bridgePort,
                            expectedPubKeyBase64 = bridgePub,
                            isAutoReconnect = isAutoReconnect
                        )
                    }
                }
                if (nodeManager.connectViaBridge(
                        bridgePeerId = bridgeId,
                        targetPeerId = peerId,
                        expectedPubKeyBase64 = targetPub,
                        isAutoReconnect = isAutoReconnect
                    ).isSuccess
                ) {
                    return@withLock finalizeAttempt(true)
                }
            }

            if (peer != null && peer.lastIp.isNotBlank() && peer.lastPort in 1..65535 && peer.viaBridgePeerId.isNotBlank()) {
                return@withLock finalizeAttempt(
                    nodeManager.connectToPeer(
                        host = peer.lastIp,
                        port = peer.lastPort,
                        expectedPubKeyBase64 = peer.publicKeyBase64,
                        isAutoReconnect = isAutoReconnect
                    ).isSuccess
                )
            }
            false
        }
    }

    suspend fun autoConnectActiveChats(force: Boolean = false) {
        if (!identityStore.getSnapshot().isInitialized || !nodeManager.serverRunning.value) return
        val savedPeers = chatDao.getAllPeersOnce()
            .filter { peer ->
                !peer.peerId.startsWith(DEV_FAKE_PEER_PREFIX) &&
                    !transientTestPeerIds.contains(peer.peerId) &&
                    !deletingPeerIds.contains(peer.peerId) &&
                    peer.publicKeyBase64.isNotBlank()
            }
        if (savedPeers.isEmpty()) return

        val now = System.currentTimeMillis()
        // First pass: direct saved active chats (including Bridge contacts); second pass: bridged saved chats
        val orderedPeers = savedPeers.sortedBy { if (it.viaBridgePeerId.isBlank()) 0 else 1 }
        for (peer in orderedPeers) {
            if (deletingPeerIds.contains(peer.peerId)) continue
            if (nodeManager.connectedPeers.value.contains(peer.peerId)) continue
            val hasDirectEndpoint = peer.viaBridgePeerId.isBlank() && peer.lastIp.isNotBlank() && peer.lastPort in 1..65535
            val hasBridgeRoute = peer.viaBridgePeerId.isNotBlank() &&
                nodeManager.connectedPeers.value.contains(peer.viaBridgePeerId)
            if (!hasDirectEndpoint && !hasBridgeRoute) continue

            val lastAttempt = lastAutoConnectAttemptMs[peer.peerId] ?: 0L
            if (!force && now - lastAttempt < 10_000L) continue
            lastAutoConnectAttemptMs[peer.peerId] = now
            ensurePeerConnected(peer.peerId, isAutoReconnect = true)
        }
    }

    override suspend fun ensureBridgeTargetConnected(targetPeerId: String, isAutoReconnect: Boolean): Boolean {
        if (deletingPeerIds.contains(targetPeerId) ||
            targetPeerId.startsWith(DEV_FAKE_PEER_PREFIX) ||
            transientTestPeerIds.contains(targetPeerId)
        ) {
            return false
        }
        if (nodeManager.connectedPeers.value.contains(targetPeerId)) return true
        val peer = chatDao.getPeerById(targetPeerId) ?: return false
        if (peer.publicKeyBase64.isBlank() || peer.lastIp.isBlank() || peer.lastPort !in 1..65535) {
            return false
        }
        val mutex = connectMutexByPeer.computeIfAbsent(targetPeerId) { Mutex() }
        return mutex.withLock {
            if (deletingPeerIds.contains(targetPeerId)) return@withLock false
            if (nodeManager.connectedPeers.value.contains(targetPeerId)) return@withLock true
            val startGen = deleteGenerationByPeer[targetPeerId]?.get() ?: 0L
            val ok = nodeManager.connectToPeer(
                host = peer.lastIp,
                port = peer.lastPort,
                expectedPubKeyBase64 = peer.publicKeyBase64,
                isAutoReconnect = isAutoReconnect
            ).isSuccess
            if (!ok) return@withLock false
            val endGen = deleteGenerationByPeer[targetPeerId]?.get() ?: 0L
            if (endGen != startGen || deletingPeerIds.contains(targetPeerId) || chatDao.getPeerById(targetPeerId) == null) {
                nodeManager.disconnectPeer(targetPeerId)
                return@withLock false
            }
            true
        }
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

        sendingInFlightIds.add(msgId)
        val ok = try {
            var connected = ensurePeerConnected(peerId)
            var sent = if (connected) {
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
            if (!sent) {
                connected = ensurePeerConnected(peerId)
                if (connected) {
                    sent = nodeManager.sendTextMessage(
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
            sent
        } finally {
            sendingInFlightIds.remove(msgId)
        }
        chatDao.updateMessageStatus(
            msgId,
            if (ok) DeliveryStatus.SENT.name else DeliveryStatus.SENDING.name
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
        val savedKnown = chatDao.getAllPeersOnce()
            .filter { it.lastIp.isNotBlank() }
            .map { it.lastIp to it.lastPort }
        val activeDirect = nodeManager.getActiveDirectEndpoints()
        discoveryManager.scanNetworkNow((savedKnown + activeDirect).distinct())
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
        val (initW, initH) = if (
            messageType == MessageType.IMAGE ||
            messageType == MessageType.VIDEO ||
            messageType == MessageType.STICKER
        ) {
            mediaStorage.readMediaDimensions(file, isVideo = messageType == MessageType.VIDEO)
        } else {
            0 to 0
        }

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
            replyToPreview = replyPreview,
            mediaWidth = initW,
            mediaHeight = initH
        )
        chatDao.upsertMessage(activeEntity)
        chatDao.upsertPeer(
            peer.copy(
                lastMessagePreview = preview,
                lastActivityAt = now
            )
        )

        val initialPhase = if (messageType == MessageType.VIDEO) {
            TransferPhase.COMPRESSING
        } else {
            TransferPhase.UPLOADING
        }
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = msgId,
                peerId = peerId,
                fileName = fileName,
                progress = 1,
                isOutgoing = true,
                totalBytes = totalBytes,
                phase = initialPhase,
                etaSeconds = null
            )
        )

        val currentJob = coroutineContext[Job]
        if (currentJob != null) {
            outgoingTransferJobs[msgId] = currentJob
        }
        sendingInFlightIds.add(msgId)

        var compressedCandidate: File? = null
        var simOnlineHandled = false
        val ok = try {
            var fileToSend = file
            var sendFileName = fileName
            var sendMimeType = mimeType
            var sendDurationMs = audioDurationMs
            var sendTotalBytes = totalBytes

            if (messageType == MessageType.VIDEO) {
                val candidate = mediaStorage.createCompressedVideoFile(fileName)
                compressedCandidate = candidate
                val compressStartedAtMs = System.currentTimeMillis()
                val didCompress = VideoCompressor.compressIfNeeded(
                    context = appContext,
                    inputFile = file,
                    outputFile = candidate,
                    onProgress = { pct ->
                        if (cancelledTransferIds.contains(msgId)) return@compressIfNeeded
                        val cleanPct = pct.coerceIn(1, 99)
                        val etaSec = computeEtaSeconds(compressStartedAtMs, cleanPct)
                        scope.launch(Dispatchers.IO) {
                            if (cancelledTransferIds.contains(msgId)) return@launch
                            chatDao.updateTransferProgress(msgId, cleanPct, DeliveryStatus.SENDING.name)
                            if (transfersMap.containsKey(msgId)) {
                                updateTransferEntry(
                                    ActiveTransferInfo(
                                        messageId = msgId,
                                        peerId = peerId,
                                        fileName = sendFileName,
                                        progress = cleanPct,
                                        isOutgoing = true,
                                        totalBytes = sendTotalBytes,
                                        phase = TransferPhase.COMPRESSING,
                                        etaSeconds = etaSec
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
                    mediaStorage.deleteMediaFile(file.absolutePath)
                    fileToSend = candidate
                    val baseName = fileName.substringBeforeLast('.').ifBlank { "video" }
                    sendFileName = "$baseName.mp4"
                    sendMimeType = "video/mp4"
                    sendTotalBytes = candidate.length()
                    if (sendDurationMs <= 0L) {
                        sendDurationMs = mediaStorage.readAudioDurationMs(candidate)
                    }
                    val (compW, compH) = mediaStorage.readMediaDimensions(candidate, isVideo = true)
                    val updatedPreview = "Video: $sendFileName"
                    activeEntity = activeEntity.copy(
                        textContent = updatedPreview,
                        mediaLocalPath = fileToSend.absolutePath,
                        fileName = sendFileName,
                        mimeType = sendMimeType,
                        fileSizeBytes = sendTotalBytes,
                        audioDurationMs = sendDurationMs,
                        transferProgress = 1,
                        mediaWidth = if (compW > 0) compW else activeEntity.mediaWidth,
                        mediaHeight = if (compH > 0) compH else activeEntity.mediaHeight
                    )
                    chatDao.upsertMessage(activeEntity)
                    chatDao.updatePeerLastPreview(peerId, updatedPreview, now)
                }
            }

            if (cancelledTransferIds.contains(msgId)) {
                false
            } else if (isDevSimulatedOnline(peerId)) {
                simOnlineHandled = true
                chatDao.updateTransferProgress(msgId, 100, DeliveryStatus.DELIVERED.name)
                true
            } else {
                chatDao.updateTransferProgress(msgId, 1, DeliveryStatus.SENDING.name)
                updateTransferEntry(
                    ActiveTransferInfo(
                        messageId = msgId,
                        peerId = peerId,
                        fileName = sendFileName,
                        progress = 1,
                        isOutgoing = true,
                        totalBytes = sendTotalBytes,
                        phase = TransferPhase.UPLOADING,
                        etaSeconds = null
                    )
                )
                val uploadStartedAtMs = System.currentTimeMillis()
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
                        mediaWidth = activeEntity.mediaWidth,
                        mediaHeight = activeEntity.mediaHeight,
                        isCancelled = { cancelledTransferIds.contains(msgId) },
                        onProgress = { pct ->
                            if (cancelledTransferIds.contains(msgId)) return@sendMediaOrFile
                            val cleanPct = pct.coerceIn(1, 100)
                            val etaSec = computeEtaSeconds(uploadStartedAtMs, cleanPct)
                            if (cleanPct < 100) {
                                chatDao.updateTransferProgress(msgId, cleanPct, DeliveryStatus.SENDING.name)
                                if (transfersMap.containsKey(msgId)) {
                                    updateTransferEntry(
                                        ActiveTransferInfo(
                                            messageId = msgId,
                                            peerId = peerId,
                                            fileName = sendFileName,
                                            progress = cleanPct,
                                            isOutgoing = true,
                                            totalBytes = sendTotalBytes,
                                            phase = TransferPhase.UPLOADING,
                                            etaSeconds = etaSec
                                        )
                                    )
                                }
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
            sendingInFlightIds.remove(msgId)
            if (currentJob != null) {
                outgoingTransferJobs.remove(msgId, currentJob)
            } else {
                outgoingTransferJobs.remove(msgId)
            }
            removeTransferEntry(msgId)
        }

        if (!cancelledTransferIds.remove(msgId) && !simOnlineHandled) {
            chatDao.updateTransferProgress(
                msgId,
                100,
                if (ok) DeliveryStatus.SENT.name else DeliveryStatus.SENDING.name
            )
        }
        return ok
    }

    suspend fun cancelTransfer(messageId: String) {
        val msg = chatDao.getMessageById(messageId)
        cancelledTransferIds.add(messageId)
        incomingTransferStartTimes.remove(messageId)
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
        incomingTransferStartTimes[messageId] = System.currentTimeMillis()
        chatDao.updateTransferProgress(messageId, 1, DeliveryStatus.SENDING.name)
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = messageId,
                peerId = msg.peerId,
                fileName = msg.fileName ?: "file",
                progress = 1,
                isOutgoing = false,
                totalBytes = msg.fileSizeBytes,
                phase = TransferPhase.DOWNLOADING,
                etaSeconds = null
            )
        )
        val requested = nodeManager.sendFileRetryRequest(msg.peerId, messageId)
        if (!requested) {
            incomingTransferStartTimes.remove(messageId)
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

    private fun persistDismissedPeers() {
        dismissedPrefs.edit().putStringSet("ids", HashSet(dismissedPeerIds)).apply()
    }

    suspend fun deleteConversation(peerId: String) {
        deletingPeerIds.add(peerId)
        deleteGenerationByPeer.computeIfAbsent(peerId) { AtomicLong(0L) }.incrementAndGet()
        dismissedPeerIds.add(peerId)
        persistDismissedPeers()
        try {
            if (_unreadDivider.value?.peerId == peerId) {
                _unreadDivider.value = null
            }
            _typingPeers.update { it - peerId }
            _devOnlineFakePeers.update { it - peerId }
            lastAutoConnectAttemptMs.remove(peerId)
            onClearPeerNotification?.invoke(peerId)

            val activeTransferIds = transfersMap.values
                .filter { it.peerId == peerId }
                .map { it.messageId }
            activeTransferIds.forEach { msgId ->
                cancelledTransferIds.add(msgId)
                outgoingTransferJobs.remove(msgId)?.cancel()
                incomingTransferStartTimes.remove(msgId)
                transfersMap.remove(msgId)
            }
            if (activeTransferIds.isNotEmpty()) {
                publishTransfersSnapshot()
            }

            nodeManager.disconnectPeer(peerId)
            chatDao.deleteAllMessagesForPeer(peerId)
            chatDao.deletePeer(peerId)
        } finally {
            deletingPeerIds.remove(peerId)
        }
    }

    override suspend fun isKnownPeer(peerId: String): Boolean {
        return !deletingPeerIds.contains(peerId) &&
            (transientTestPeerIds.contains(peerId) || chatDao.getPeerById(peerId) != null)
    }

    override suspend fun isPeerDismissed(peerId: String): Boolean {
        return dismissedPeerIds.contains(peerId)
    }

    override suspend fun onPeerProfileUpdated(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String
    ) {
        if (deletingPeerIds.contains(peerId) || transientTestPeerIds.contains(peerId)) return
        if (chatDao.getPeerById(peerId) == null) return
        onPeerHandshaked(
            peerId = peerId,
            publicKeyBase64 = publicKeyBase64,
            remoteIp = remoteIp,
            remoteListenPort = remoteListenPort,
            username = username,
            avatarBase64 = avatarBase64,
            viaBridgePeerId = viaBridgePeerId
        )
    }

    override suspend fun onPeerHandshaked(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String
    ) {
        if (deletingPeerIds.contains(peerId) || transientTestPeerIds.contains(peerId)) return
        if (dismissedPeerIds.remove(peerId)) {
            persistDismissedPeers()
        }
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
            unreadCount = existing?.unreadCount ?: 0,
            viaBridgePeerId = viaBridgePeerId
        )
        chatDao.upsertPeer(updated)
        if (!peerId.startsWith(DEV_FAKE_PEER_PREFIX)) {
            scope.launch(Dispatchers.IO) {
                flushPendingOutgoingMessages(peerId)
            }
        }
    }

    override suspend fun onBridgePeersUpdated(bridgePeerId: String, availablePeerIds: Set<String>) {
        if (availablePeerIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (targetPeerId in availablePeerIds) {
                if (deletingPeerIds.contains(targetPeerId)) continue
                val peer = chatDao.getPeerById(targetPeerId) ?: continue
                if (peer.viaBridgePeerId != bridgePeerId && !nodeManager.connectedPeers.value.contains(targetPeerId)) {
                    chatDao.upsertPeer(peer.copy(viaBridgePeerId = bridgePeerId))
                }
                if (!nodeManager.connectedPeers.value.contains(targetPeerId)) {
                    if (ensurePeerConnected(targetPeerId, isAutoReconnect = true)) {
                        flushPendingOutgoingMessages(targetPeerId)
                    }
                }
            }
        }
    }

    suspend fun flushPendingOutgoingMessages(peerId: String) {
        val mutex = outboxMutexByPeer.computeIfAbsent(peerId) { Mutex() }
        mutex.withLock {
            val isSim = isDevSimulatedOnline(peerId)
            val isRealConnected = nodeManager.connectedPeers.value.contains(peerId)
            if (!isSim && !isRealConnected) return

            val pending = chatDao.getPendingOutgoingMessagesChronological(peerId)
            for (msg in pending) {
                if (sendingInFlightIds.contains(msg.messageId) ||
                    outgoingTransferJobs.containsKey(msg.messageId) ||
                    cancelledTransferIds.contains(msg.messageId)
                ) {
                    continue
                }
                if (!isDevSimulatedOnline(peerId) && !nodeManager.connectedPeers.value.contains(peerId)) {
                    break
                }
                sendingInFlightIds.add(msg.messageId)
                var abortLoop = false
                try {
                    if (isDevSimulatedOnline(peerId)) {
                        chatDao.updateTransferProgress(msg.messageId, 100, DeliveryStatus.DELIVERED.name)
                        if (msg.type == MessageType.TEXT.name) {
                            scope.launch(Dispatchers.IO) {
                                delay(450L)
                                onDeliveryReceipt(peerId, msg.messageId, DeliveryStatus.READ)
                            }
                        }
                    } else if (msg.type == MessageType.TEXT.name) {
                        val wireAuthor = msg.replyToSenderTag?.let {
                            if (it == ReplySenderTags.ME) "ME" else "PEER"
                        }
                        val ok = nodeManager.sendTextMessage(
                            peerId = peerId,
                            messageId = msg.messageId,
                            text = msg.textContent,
                            selfDestructSec = msg.selfDestructSeconds,
                            createdAt = msg.createdAt,
                            replyToMessageId = msg.replyToMessageId,
                            replyToAuthorType = wireAuthor,
                            replyToPreview = msg.replyToPreview
                        )
                        if (ok) {
                            chatDao.updateMessageStatus(msg.messageId, DeliveryStatus.SENT.name)
                        } else {
                            chatDao.updateMessageStatus(msg.messageId, DeliveryStatus.SENDING.name)
                            abortLoop = true
                        }
                    } else {
                        val file = msg.mediaLocalPath?.let { File(it) }
                        if (file == null || !file.exists()) {
                            chatDao.updateMessageStatus(msg.messageId, DeliveryStatus.FAILED.name)
                        } else {
                            val msgType = MessageType.entries.firstOrNull { it.name == msg.type } ?: MessageType.FILE
                            val wireAuthor = msg.replyToSenderTag?.let {
                                if (it == ReplySenderTags.ME) "ME" else "PEER"
                            }
                            val flushJob = coroutineContext[Job]
                            if (flushJob != null) {
                                outgoingTransferJobs[msg.messageId] = flushJob
                            }
                            val flushUploadStartMs = System.currentTimeMillis()
                            updateTransferEntry(
                                ActiveTransferInfo(
                                    messageId = msg.messageId,
                                    peerId = peerId,
                                    fileName = msg.fileName ?: file.name,
                                    progress = 1,
                                    isOutgoing = true,
                                    totalBytes = file.length(),
                                    phase = TransferPhase.UPLOADING,
                                    etaSeconds = null
                                )
                            )
                            val ok = try {
                                nodeManager.sendMediaOrFile(
                                    peerId = peerId,
                                    messageId = msg.messageId,
                                    messageType = msgType,
                                    file = file,
                                    fileName = msg.fileName ?: file.name,
                                    mimeType = msg.mimeType ?: "application/octet-stream",
                                    audioDurationMs = msg.audioDurationMs,
                                    selfDestructSec = msg.selfDestructSeconds,
                                    createdAt = msg.createdAt,
                                    replyToMessageId = msg.replyToMessageId,
                                    replyToAuthorType = wireAuthor,
                                    replyToPreview = msg.replyToPreview,
                                    mediaWidth = msg.mediaWidth,
                                    mediaHeight = msg.mediaHeight,
                                    isCancelled = { cancelledTransferIds.contains(msg.messageId) },
                                    onProgress = { pct ->
                                        if (cancelledTransferIds.contains(msg.messageId)) return@sendMediaOrFile
                                        val cleanPct = pct.coerceIn(1, 99)
                                        val etaSec = computeEtaSeconds(flushUploadStartMs, cleanPct)
                                        updateTransferEntry(
                                            ActiveTransferInfo(
                                                messageId = msg.messageId,
                                                peerId = peerId,
                                                fileName = msg.fileName ?: file.name,
                                                progress = cleanPct,
                                                isOutgoing = true,
                                                totalBytes = file.length(),
                                                phase = TransferPhase.UPLOADING,
                                                etaSeconds = etaSec
                                            )
                                        )
                                    }
                                )
                            } finally {
                                if (flushJob != null) {
                                    outgoingTransferJobs.remove(msg.messageId, flushJob)
                                } else {
                                    outgoingTransferJobs.remove(msg.messageId)
                                }
                                removeTransferEntry(msg.messageId)
                            }
                            if (cancelledTransferIds.remove(msg.messageId)) {
                                Unit
                            } else if (ok) {
                                chatDao.updateTransferProgress(msg.messageId, 100, DeliveryStatus.SENT.name)
                            } else {
                                chatDao.updateTransferProgress(msg.messageId, 100, DeliveryStatus.SENDING.name)
                                abortLoop = true
                            }
                        }
                    }
                } finally {
                    sendingInFlightIds.remove(msg.messageId)
                }
                if (abortLoop) break
            }
        }
    }

    override suspend fun onPeerDisconnected(peerId: String) {
        _typingPeers.update { it - peerId }
        val toRemove = transfersMap.values.filter { it.peerId == peerId && !it.isOutgoing }.map { it.messageId }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach {
                incomingTransferStartTimes.remove(it)
                transfersMap.remove(it)
            }
            publishTransfersSnapshot()
        }
    }

    private suspend fun recordScrolledUpUnreadAnchor(peerId: String, messageId: String) {
        val currentDiv = _unreadDivider.value
        val existingAnchorMsg = if (currentDiv != null && currentDiv.peerId == peerId) {
            chatDao.getMessageById(currentDiv.anchorMessageId)
        } else {
            null
        }
        if (currentDiv != null && existingAnchorMsg != null && existingAnchorMsg.status != DeliveryStatus.READ.name) {
            _unreadDivider.value = currentDiv.copy(unreadCount = currentDiv.unreadCount + 1)
        } else {
            _unreadDivider.value = UnreadDividerInfo(
                peerId = peerId,
                anchorMessageId = messageId,
                unreadCount = 1
            )
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
        if (deletingPeerIds.contains(peerId)) return
        _typingPeers.update { it - peerId }
        val isChatOpenInFg = isAppInForegroundProvider() && currentlyOpenPeerId == peerId
        val isActivelyViewed = isChatOpenInFg && isOpenChatAtBottom
        val now = System.currentTimeMillis()
        val expiresAt = if (isActivelyViewed && selfDestructSec > 0) {
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
            status = if (isActivelyViewed) DeliveryStatus.READ.name else DeliveryStatus.DELIVERED.name,
            selfDestructSeconds = selfDestructSec,
            createdAt = createdAt,
            readAt = if (isActivelyViewed) now else null,
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
                    unreadCount = if (isActivelyViewed) 0 else peer.unreadCount + 1
                )
            )
        }
        if (isActivelyViewed) {
            nodeManager.sendReadReceipt(peerId, messageId)
        } else if (isChatOpenInFg) {
            recordScrolledUpUnreadAnchor(peerId, messageId)
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
        replyToPreview: String?,
        mediaWidth: Int,
        mediaHeight: Int
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
            replyToPreview = replyToPreview,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight
        )
        chatDao.upsertMessage(msg)
        incomingTransferStartTimes[messageId] = System.currentTimeMillis()
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = messageId,
                peerId = peerId,
                fileName = fileName,
                progress = 1,
                isOutgoing = false,
                totalBytes = totalBytes,
                phase = TransferPhase.DOWNLOADING,
                etaSeconds = null
            )
        )
        return targetFile
    }

    override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {
        if (cancelledTransferIds.contains(messageId)) return
        chatDao.updateTransferProgress(messageId, progress, DeliveryStatus.SENDING.name)
        val existing = transfersMap[messageId]
        if (existing != null) {
            val etaSec = incomingTransferStartTimes[messageId]?.let { computeEtaSeconds(it, progress) }
            updateTransferEntry(
                existing.copy(
                    progress = progress,
                    phase = TransferPhase.DOWNLOADING,
                    etaSeconds = etaSec
                )
            )
        }
    }

    override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {
        incomingTransferStartTimes.remove(messageId)
        removeTransferEntry(messageId)
        if (cancelledTransferIds.remove(messageId)) {
            mediaStorage.deleteMediaFile(file.absolutePath)
            return
        }
        val existing = chatDao.getMessageById(messageId) ?: return
        val isChatOpenInFg = isAppInForegroundProvider() && currentlyOpenPeerId == peerId
        val isActivelyViewed = isChatOpenInFg && isOpenChatAtBottom
        val now = System.currentTimeMillis()
        val expiresAt = if (isActivelyViewed && existing.selfDestructSeconds > 0) {
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
        val (measuredW, measuredH) = if (
            existing.type == MessageType.IMAGE.name ||
            existing.type == MessageType.VIDEO.name ||
            existing.type == MessageType.STICKER.name
        ) {
            mediaStorage.readMediaDimensions(file, isVideo = existing.type == MessageType.VIDEO.name)
        } else {
            0 to 0
        }
        chatDao.upsertMessage(
            existing.copy(
                textContent = finalPreview,
                mediaLocalPath = file.absolutePath,
                transferProgress = 100,
                status = if (isActivelyViewed) DeliveryStatus.READ.name else DeliveryStatus.DELIVERED.name,
                readAt = if (isActivelyViewed) now else null,
                expiresAt = expiresAt,
                mediaWidth = if (measuredW > 0) measuredW else existing.mediaWidth,
                mediaHeight = if (measuredH > 0) measuredH else existing.mediaHeight
            )
        )
        val peer = chatDao.getPeerById(peerId)
        if (peer != null) {
            chatDao.upsertPeer(
                peer.copy(
                    lastMessagePreview = finalPreview,
                    lastActivityAt = now,
                    unreadCount = if (isActivelyViewed) 0 else peer.unreadCount + 1
                )
            )
        }
        if (isActivelyViewed) {
            nodeManager.sendReadReceipt(peerId, messageId)
        } else if (isChatOpenInFg) {
            recordScrolledUpUnreadAnchor(peerId, messageId)
        } else {
            val senderName = peer?.username ?: "Chatso"
            onIncomingNotification?.invoke(peerId, senderName, finalPreview, peer?.avatarPath)
        }
    }

    override suspend fun onIncomingFileCancelledBySender(peerId: String, messageId: String) {
        cancelledTransferIds.add(messageId)
        incomingTransferStartTimes.remove(messageId)
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
        val retryStartMs = System.currentTimeMillis()
        updateTransferEntry(
            ActiveTransferInfo(
                messageId = messageId,
                peerId = peerId,
                fileName = fileName,
                progress = 1,
                isOutgoing = true,
                totalBytes = totalBytes,
                phase = TransferPhase.UPLOADING,
                etaSeconds = null
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
                mediaWidth = msg.mediaWidth,
                mediaHeight = msg.mediaHeight,
                isCancelled = { cancelledTransferIds.contains(messageId) },
                onProgress = { pct ->
                    if (cancelledTransferIds.contains(messageId)) return@sendMediaOrFile
                    val cleanPct = pct.coerceIn(1, 99)
                    val etaSec = computeEtaSeconds(retryStartMs, cleanPct)
                    if (transfersMap.containsKey(messageId)) {
                        updateTransferEntry(
                            ActiveTransferInfo(
                                messageId = messageId,
                                peerId = peerId,
                                fileName = fileName,
                                progress = cleanPct,
                                isOutgoing = true,
                                totalBytes = totalBytes,
                                phase = TransferPhase.UPLOADING,
                                etaSeconds = etaSec
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
        incomingTransferStartTimes.remove(messageId)
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
        if (deletingPeerIds.contains(peerId) || dismissedPeerIds.contains(peerId)) return
        _typingPeers.update { if (isTyping) it + peerId else it - peerId }
    }

    override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {
        if (deletingPeerIds.contains(peerId) || dismissedPeerIds.contains(peerId)) return
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
        selfDestructSeconds: Int = 0,
        viaBridgePeerId: String = ""
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
            avatarBase64 = null,
            viaBridgePeerId = viaBridgePeerId
        )
        if (selfDestructSeconds > 0) {
            chatDao.updatePeerSelfDestruct(fakePeerId, selfDestructSeconds)
        }
        _devOnlineFakePeers.update { it + fakePeerId }
        return chatDao.getPeerById(fakePeerId)!!
    }

    suspend fun devCreateBridgedFakePeer(
        usernameInput: String = "",
        selfDestructSeconds: Int = 0
    ): PeerEntity {
        val name = usernameInput.trim().ifBlank {
            val existingCount = chatDao.getAllPeersOnce().count { it.peerId.startsWith(DEV_FAKE_PEER_PREFIX) }
            "Bridge Peer #${existingCount + 1}"
        }
        return devCreateFakePeer(
            usernameInput = name,
            selfDestructSeconds = selfDestructSeconds,
            viaBridgePeerId = "dev_bridge_sandbox"
        )
    }

    suspend fun devMarkOutgoingMessagesRead(peerId: String): Int {
        val pending = chatDao.getUnreadOutgoingMessages(peerId)
        for (msg in pending) {
            onDeliveryReceipt(peerId, msg.messageId, DeliveryStatus.READ)
        }
        return pending.size
    }

    fun devResetBridgeTelemetry() {
        nodeManager.resetBridgeTelemetry()
    }

    suspend fun devRunLoopbackBridgeTest(): Result<String> {
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

        val receivedOnC = kotlinx.coroutines.CompletableDeferred<String>()
        val discA = DiscoveryManager(appContext, scope)
        val discC = DiscoveryManager(appContext, scope)
        val nodeA = P2PNodeManager(
            scope = scope,
            discoveryManager = discA,
            listener = object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String): Boolean = true
                override suspend fun onPeerHandshaked(peerId: String, publicKeyBase64: String, remoteIp: String, remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String) {}
                override suspend fun onPeerDisconnected(peerId: String) {}
                override suspend fun onIncomingTextMessage(peerId: String, messageId: String, text: String, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?) {}
                override suspend fun onIncomingFileStart(peerId: String, messageId: String, messageType: MessageType, fileName: String, mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?, mediaWidth: Int, mediaHeight: Int): File = File(appContext.cacheDir, "noop_$messageId")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
            }
        )
        val nodeC = P2PNodeManager(
            scope = scope,
            discoveryManager = discC,
            listener = object : P2PEventListener {
                override suspend fun isKnownPeer(peerId: String): Boolean = true
                override suspend fun onPeerHandshaked(peerId: String, publicKeyBase64: String, remoteIp: String, remoteListenPort: Int, username: String, avatarBase64: String?, viaBridgePeerId: String) {}
                override suspend fun onPeerDisconnected(peerId: String) {}
                override suspend fun onIncomingTextMessage(peerId: String, messageId: String, text: String, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?) {
                    receivedOnC.complete(text)
                }
                override suspend fun onIncomingFileStart(peerId: String, messageId: String, messageType: MessageType, fileName: String, mimeType: String, totalBytes: Long, audioDurationMs: Long, selfDestructSec: Int, createdAt: Long, replyToMessageId: String?, replyToSenderTag: String?, replyToPreview: String?, mediaWidth: Int, mediaHeight: Int): File = File(appContext.cacheDir, "noop_$messageId")
                override suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int) {}
                override suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File) {}
                override suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus) {}
                override suspend fun onTypingChanged(peerId: String, isTyping: Boolean) {}
                override suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int) {}
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
            val delivered = kotlinx.coroutines.withTimeoutOrNull(4000L) { receivedOnC.await() }
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

        val (mediaW, mediaH) = mediaStorage.readMediaDimensions(tempFile, isVideo = false)
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
            replyToPreview = null,
            mediaWidth = mediaW,
            mediaHeight = mediaH
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
        if (online) {
            scope.launch(Dispatchers.IO) {
                flushPendingOutgoingMessages(peerId)
            }
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
        autoConnectJob?.cancel()
        autoConnectJob = null
        discoveryManager.shutdown()
        nodeManager.disconnectAll()
    }
}
