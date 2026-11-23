package com.example.network

import android.util.Base64
import com.example.crypto.CryptoEngine
import com.example.crypto.SessionCipher
import com.example.data.BridgeRouteInfo
import com.example.data.BridgeTelemetry
import com.example.data.DeliveryStatus
import com.example.data.IrohRelayMode
import com.example.data.IrohRelayState
import com.example.data.IrohRelayStatus
import com.example.data.LocalIdentity
import com.example.data.MessageType
import com.example.data.ReplySenderTags
import java.util.concurrent.atomic.AtomicLong
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

interface P2PEventListener {
    suspend fun isKnownPeer(peerId: String): Boolean
    suspend fun isPeerDismissed(peerId: String): Boolean = false
    suspend fun isInstanceDismissed(instanceId: String): Boolean = false
    suspend fun getKnownPeerInstanceId(peerId: String): String = ""
    suspend fun onPeerHandshaked(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String = ""
    )
    suspend fun onPeerHandshakedWithMeta(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String = "",
        remoteInstanceId: String = "",
        isInviteAuthorized: Boolean = false,
        allowRestoreDismissed: Boolean = true,
        remoteIrohId: String = "",
        remoteIrohRelayUrl: String = ""
    ) {
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
    suspend fun onPeerProfileUpdated(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String = ""
    ) {
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
    suspend fun onPeerProfileUpdatedWithMeta(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?,
        viaBridgePeerId: String = "",
        remoteInstanceId: String = "",
        remoteIrohId: String = "",
        remoteIrohRelayUrl: String = ""
    ) {
        onPeerProfileUpdated(
            peerId = peerId,
            publicKeyBase64 = publicKeyBase64,
            remoteIp = remoteIp,
            remoteListenPort = remoteListenPort,
            username = username,
            avatarBase64 = avatarBase64,
            viaBridgePeerId = viaBridgePeerId
        )
    }
    suspend fun onPeerDisconnected(peerId: String)
    suspend fun onPeerForegroundChanged(peerId: String, inForeground: Boolean) {}
    suspend fun onBridgePeersUpdated(bridgePeerId: String, availablePeerIds: Set<String>) {}
    suspend fun ensureBridgeTargetConnected(
        targetPeerId: String,
        isAutoReconnect: Boolean = false
    ): Boolean = false
    suspend fun onIncomingTextMessage(
        peerId: String,
        messageId: String,
        text: String,
        selfDestructSec: Int,
        createdAt: Long,
        replyToMessageId: String? = null,
        replyToSenderTag: String? = null,
        replyToPreview: String? = null
    )
    suspend fun onIncomingFileStart(
        peerId: String,
        messageId: String,
        messageType: MessageType,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        audioDurationMs: Long,
        selfDestructSec: Int,
        createdAt: Long,
        replyToMessageId: String? = null,
        replyToSenderTag: String? = null,
        replyToPreview: String? = null,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0
    ): File
    suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int)
    suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File)
    suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus)
    suspend fun onReceiptSendFailed(peerId: String, messageId: String, status: DeliveryStatus) {}
    suspend fun onReceiptQueryReceived(peerId: String, messageIds: List<String>) {}
    suspend fun onReceiptBatchReceived(peerId: String, receipts: List<Pair<String, DeliveryStatus>>) {}
    suspend fun isPeerVerifiedByMe(peerId: String): Boolean = false
    suspend fun onPeerVerificationChanged(peerId: String, verifiedMe: Boolean) {}
    suspend fun onIncomingReaction(peerId: String, messageId: String, emoji: String) {}
    suspend fun onIncomingFileCancelledBySender(peerId: String, messageId: String) {}
    suspend fun onOutgoingFileCancelledByReceiver(peerId: String, messageId: String) {}
    suspend fun onPeerRequestedFileRetry(peerId: String, messageId: String) {}
    suspend fun onRemoteFileUnavailable(peerId: String, messageId: String) {}
    suspend fun onTypingChanged(peerId: String, isTyping: Boolean)
    suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int)
}

class P2PNodeManager(
    private val scope: CoroutineScope,
    private val discoveryManager: DiscoveryManager,
    private val listener: P2PEventListener,
    private val irohTransport: IrohTransport = NativeIrohTransport()
) {
    companion object {
        private const val KIND_HANDSHAKE_HELLO: Byte = 0x03
        private const val KIND_HANDSHAKE_WELCOME: Byte = 0x04
        private const val KIND_HANDSHAKE_REJECT: Byte = 0x05
        private const val KIND_BRIDGE_SESSION_READY: Byte = 0x06
        private const val KIND_ENCRYPTED_CONTROL: Byte = 0x10
        private const val KIND_ENCRYPTED_CHUNK: Byte = 0x11
        private const val CHUNK_SIZE = 48 * 1024 // 48 KB encrypted streaming chunks
        private const val MAX_FRAME_BYTES = 512 * 1024
    }

    private inner class ActivePeerSession(
        val peerId: String,
        val socket: Socket?,
        val output: DataOutputStream?,
        val irohChannel: IrohPeerChannel? = null,
        val cipher: SessionCipher,
        val bridgePeerId: String? = null,
        val remotePubB64: String = "",
        val remoteIp: String = "",
        val remoteInstanceId: String = "",
        @Volatile var remoteIrohId: String = "",
        @Volatile var remoteIrohRelayUrl: String = "",
        val isInviteAuthorized: Boolean = false,
        @Volatile var remoteListenPort: Int = LocalIdentity.DEFAULT_TCP_PORT,
        @Volatile var remoteUsername: String = "",
        @Volatile var remoteAvatarBase64: String? = null,
        @Volatile var remoteDiscoverable: Boolean = true,
        @Volatile var isSilentDismissed: Boolean = false,
        val writeMutex: Mutex = Mutex(),
        val incomingTransfers: ConcurrentHashMap<String, IncomingTransfer> = ConcurrentHashMap(),
        val cancelledIncomingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    ) {
        @Volatile
        var closed: Boolean = false

        val isClosed: Boolean
            get() = closed ||
                socket?.isClosed == true ||
                irohChannel?.isClosed == true ||
                (bridgePeerId != null && sessions[bridgePeerId]?.isClosed != false)

        suspend fun sendEncryptedFrame(kind: Byte, plaintext: ByteArray): Unit = withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (isClosed) throw java.io.IOException("Session closed")
                val encrypted = cipher.encrypt(kind, plaintext)
                if (bridgePeerId != null) {
                    val relay = sessions[bridgePeerId]?.takeIf { !it.isClosed }
                        ?: throw java.io.IOException("Bridge peer offline")
                    val envelope = JSONObject().apply {
                        put("cmd", "BRIDGE_FRAME")
                        put("from", currentIdentity.peerId)
                        put("to", peerId)
                        put("frameKind", kind.toInt())
                        put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    }.toString().toByteArray(Charsets.UTF_8)
                    relay.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, envelope)
                } else if (irohChannel != null) {
                    irohChannel.writeFrame(kind, encrypted)
                } else {
                    val out = output ?: throw java.io.IOException("Direct output unavailable")
                    out.writeByte(kind.toInt())
                    out.writeInt(encrypted.size)
                    out.write(encrypted)
                    out.flush()
                }
            }
        }

        fun closeQuietly() {
            closed = true
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            try {
                irohChannel?.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class IncomingTransfer(
        val file: File,
        val outputStream: FileOutputStream,
        val totalBytes: Long,
        var receivedBytes: Long = 0L,
        var lastReportedProgress: Int = 0,
        @Volatile var cancelled: Boolean = false
    )

    private val runtimeFallbackInstanceId = UUID.randomUUID().toString()

    private fun effectiveInstanceId(identity: LocalIdentity = currentIdentity): String {
        return identity.instanceId.ifBlank { runtimeFallbackInstanceId }
    }

    @Volatile
    private var currentIdentity: LocalIdentity = LocalIdentity()

    @Volatile
    private var localAppInForeground: Boolean = false

    @Volatile
    private var localAvatarBase64Provider: suspend () -> String? = { null }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val irohLifecycleMutex = Mutex()
    private var irohSession: IrohEndpointSession? = null
    private var irohAcceptJob: Job? = null
    private var irohLifecycleJob: Job? = null
    private var activeIrohConfigKey: String? = null

    private val _irohStatus = MutableStateFlow(IrohRelayStatus())
    val irohStatus: StateFlow<IrohRelayStatus> = _irohStatus.asStateFlow()

    private val sessions = ConcurrentHashMap<String, ActivePeerSession>()
    private val pendingBridgedHandshakes =
        ConcurrentHashMap<String, CompletableDeferred<Pair<Byte, ByteArray>>>()
    private val bridgedConnectMutexByPeer = ConcurrentHashMap<String, Mutex>()

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    private val _foregroundPeers = MutableStateFlow<Set<String>>(emptySet())
    val foregroundPeers: StateFlow<Set<String>> = _foregroundPeers.asStateFlow()

    private val _boundPort = MutableStateFlow(LocalIdentity.DEFAULT_TCP_PORT)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val bridgeRelayedFrames = AtomicLong(0L)
    private val bridgeRelayedBytes = AtomicLong(0L)
    private val bridgeFailedRelays = AtomicLong(0L)
    private val bridgeRouteStats = ConcurrentHashMap<String, BridgeRouteInfo>()
    private val _bridgeTelemetry = MutableStateFlow(BridgeTelemetry())
    val bridgeTelemetry: StateFlow<BridgeTelemetry> = _bridgeTelemetry.asStateFlow()

    init {
        discoveryManager.setBridgedPeersProvider {
            if (!currentIdentity.bridgeEnabled) {
                emptyList()
            } else {
                val myInst = effectiveInstanceId()
                sessions.values
                    .filter { s ->
                        s.bridgePeerId == null &&
                            !s.isClosed &&
                            !s.isSilentDismissed &&
                            !s.peerId.startsWith("loopback_") &&
                            (s.remoteInstanceId.isBlank() || s.remoteInstanceId != myInst) &&
                            s.remoteDiscoverable &&
                            s.remotePubB64.isNotBlank()
                    }
                    .map { s ->
                        DiscoveryManager.BridgedPeerInfo(
                            peerId = s.peerId,
                            username = s.remoteUsername.ifBlank { s.remoteIp.ifBlank { "Chatso User" } },
                            publicKeyBase64 = s.remotePubB64,
                            listenPort = s.remoteListenPort,
                            isDiscoverable = true,
                            instanceId = s.remoteInstanceId
                        )
                    }
            }
        }
    }

    fun setAvatarProvider(provider: suspend () -> String?) {
        localAvatarBase64Provider = provider
    }

    fun setLocalAppInForeground(inForeground: Boolean) {
        if (localAppInForeground == inForeground) return
        localAppInForeground = inForeground
        scope.launch(Dispatchers.IO) {
            broadcastAppState(inForeground)
        }
    }

    fun parseIrohTicket(ticket: String): Pair<String, String>? = irohTransport.parseTicket(ticket)

    fun updateIdentityAndEnsureServer(identity: LocalIdentity) {
        val portChanged = currentIdentity.listenPort != identity.listenPort
        val bridgeChanged = currentIdentity.bridgeEnabled != identity.bridgeEnabled
        val prevConfigKey = buildIrohConfig(currentIdentity).first
        val nextConfigKey = buildIrohConfig(identity).first
        val wasInit = currentIdentity.isInitialized
        currentIdentity = identity
        discoveryManager.applyIdentityPolicy(identity)

        if (!identity.isInitialized) {
            stopServer()
            stopIroh()
            refreshBridgeTelemetry()
            return
        }
        if (!wasInit || portChanged || !_serverRunning.value) {
            restartServer(identity.listenPort)
        } else {
            scope.launch(Dispatchers.IO) {
                broadcastProfileSync()
                if (bridgeChanged) {
                    broadcastBridgePeers()
                }
            }
        }

        val needsIrohEnsure = prevConfigKey != nextConfigKey ||
            (identity.irohRelayMode != IrohRelayMode.DISABLED &&
                _irohStatus.value.state != IrohRelayState.ERROR &&
                _irohStatus.value.state != IrohRelayState.STARTING &&
                irohSession?.isClosed != false)
        if (needsIrohEnsure) {
            scheduleIrohSync(forceRestart = prevConfigKey != nextConfigKey)
        }
        refreshBridgeTelemetry()
    }

    suspend fun reconnectIrohRelay() = withContext(Dispatchers.IO) {
        irohLifecycleMutex.withLock {
            ensureIrohStartedLocked(forceRestart = true)
        }
    }

    private fun configuredRelayUrlSummary(identity: LocalIdentity): String {
        return when (identity.irohRelayMode) {
            IrohRelayMode.DISABLED -> ""
            IrohRelayMode.PUBLIC -> identity.irohPublicRelayUrl.trim()
            IrohRelayMode.PRIVATE -> identity.irohPrivateRelayUrl.trim()
        }
    }

    private fun buildIrohConfig(identity: LocalIdentity): Pair<String, IrohTransportConfig?> {
        if (!identity.isInitialized || identity.irohRelayMode == IrohRelayMode.DISABLED) {
            return "DISABLED" to null
        }
        val relayUrls = when (identity.irohRelayMode) {
            IrohRelayMode.DISABLED -> emptyList()
            IrohRelayMode.PUBLIC -> {
                val single = identity.irohPublicRelayUrl.trim()
                if (single.isEmpty()) {
                    emptyList()
                } else {
                    listOfNotNull(IrohRelayUrlUtils.normalizeSingleRelayUrl(single))
                }
            }
            IrohRelayMode.PRIVATE -> {
                IrohRelayUrlUtils.parseRelayUrlList(identity.irohPrivateRelayUrl).orEmpty()
            }
        }
        val secretBytes = identity.irohSecretKeyBase64.trim().takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
        }
        val key = "${identity.irohRelayMode.name}|${relayUrls.joinToString(",")}|${identity.irohPrivateRelayUrl.trim()}|${identity.irohSecretKeyBase64}"
        return key to IrohTransportConfig(
            mode = identity.irohRelayMode,
            relayUrls = relayUrls,
            secretKey = secretBytes
        )
    }

    private fun scheduleIrohSync(forceRestart: Boolean) {
        irohLifecycleJob?.cancel()
        irohLifecycleJob = scope.launch(Dispatchers.IO) {
            irohLifecycleMutex.withLock {
                ensureIrohStartedLocked(forceRestart = forceRestart)
            }
        }
    }

    private suspend fun ensureIrohStartedLocked(forceRestart: Boolean) {
        val identity = currentIdentity
        val (configKey, transportConfig) = buildIrohConfig(identity)
        if (transportConfig == null) {
            stopIrohLocked(identity)
            return
        }
        val existingSession = irohSession
        if (!forceRestart && configKey == activeIrohConfigKey) {
            if (existingSession != null && !existingSession.isClosed) {
                return
            }
            if (_irohStatus.value.state == IrohRelayState.ERROR) {
                return
            }
        }

        stopIrohLocked(identity, keepState = true)
        activeIrohConfigKey = configKey

        val configuredUrl = configuredRelayUrlSummary(identity)
        if (identity.irohRelayMode == IrohRelayMode.PRIVATE && transportConfig.relayUrls.isEmpty()) {
            _irohStatus.value = IrohRelayStatus(
                mode = IrohRelayMode.PRIVATE,
                state = IrohRelayState.ERROR,
                endpointId = identity.irohEndpointId,
                configuredRelayUrl = configuredUrl,
                errorMessage = "Private relay URL is required"
            )
            return
        }

        _irohStatus.value = IrohRelayStatus(
            mode = identity.irohRelayMode,
            state = IrohRelayState.STARTING,
            endpointId = identity.irohEndpointId,
            configuredRelayUrl = configuredUrl
        )

        try {
            val started = irohTransport.startEndpoint(transportConfig)
            if (activeIrohConfigKey != configKey || currentIdentity.irohRelayMode == IrohRelayMode.DISABLED) {
                runCatching { started.close() }
                return
            }
            irohSession = started
            val endpointId = started.endpointId.ifBlank { identity.irohEndpointId }
            val activeRelay = started.activeRelayUrl.ifBlank { configuredUrl }
            _irohStatus.value = IrohRelayStatus(
                mode = identity.irohRelayMode,
                state = IrohRelayState.ONLINE,
                endpointId = endpointId,
                activeRelayUrl = activeRelay,
                configuredRelayUrl = configuredUrl
            )
            irohAcceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && !started.isClosed) {
                    val channel = try {
                        started.acceptPeerChannel()
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        if (started.isClosed) break else continue
                    }
                    if (channel == null) {
                        if (started.isClosed) break else continue
                    }
                    launch(Dispatchers.IO) {
                        handleInboundIrohChannel(channel)
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _irohStatus.value = IrohRelayStatus(
                mode = identity.irohRelayMode,
                state = IrohRelayState.ERROR,
                endpointId = identity.irohEndpointId,
                configuredRelayUrl = configuredUrl,
                errorMessage = t.message ?: "Failed to start Iroh relay"
            )
        }
    }

    private fun stopIrohLocked(identity: LocalIdentity = currentIdentity, keepState: Boolean = false) {
        irohAcceptJob?.cancel()
        irohAcceptJob = null
        val prev = irohSession
        irohSession = null
        runCatching { prev?.close() }
        activeIrohConfigKey = null
        if (!keepState) {
            _irohStatus.value = IrohRelayStatus(
                mode = identity.irohRelayMode,
                state = IrohRelayState.DISABLED,
                endpointId = identity.irohEndpointId,
                configuredRelayUrl = configuredRelayUrlSummary(identity)
            )
        }
    }

    fun stopIroh() {
        irohLifecycleJob?.cancel()
        irohLifecycleJob = null
        stopIrohLocked(currentIdentity, keepState = false)
    }

    private fun restartServer(preferredPort: Int) {
        stopServer()
        acceptJob = scope.launch(Dispatchers.IO) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", preferredPort))
                }
            } catch (_: Exception) {
                val fallbackRange = (LocalIdentity.DEFAULT_TCP_PORT..(LocalIdentity.DEFAULT_TCP_PORT + 10))
                    .filter { it != preferredPort }
                var boundFallback: ServerSocket? = null
                for (candidatePort in fallbackRange) {
                    boundFallback = runCatching {
                        ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress("0.0.0.0", candidatePort))
                        }
                    }.getOrNull()
                    if (boundFallback != null) break
                }
                boundFallback ?: try {
                    ServerSocket(0).apply { reuseAddress = true }
                } catch (_: Exception) {
                    null
                }
            }
            if (ss == null) {
                _serverRunning.value = false
                return@launch
            }
            serverSocket = ss
            _boundPort.value = ss.localPort
            _serverRunning.value = true

            while (isActive && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                launch(Dispatchers.IO) {
                    handleInboundSocket(client)
                }
            }
            _serverRunning.value = false
        }
    }

    fun stopServer() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _serverRunning.value = false
    }

    private fun sendRejectAndClose(out: DataOutputStream, socket: Socket, reason: String) {
        runCatching {
            val rej = JSONObject().apply { put("reason", reason) }.toString().toByteArray(Charsets.UTF_8)
            out.writeByte(KIND_HANDSHAKE_REJECT.toInt())
            out.writeInt(rej.size)
            out.write(rej)
            out.flush()
        }
        runCatching { socket.close() }
    }

    suspend fun connectToPeer(
        host: String,
        port: Int,
        expectedPubKeyBase64: String = "",
        inviteToken: String = "",
        isAutoReconnect: Boolean = false,
        expectedInstanceId: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            return@withContext Result.failure(IllegalStateException("Local identity is not initialized yet"))
        }
        val localInst = effectiveInstanceId(identity)
        try {
            val cleanHost = NetworkUtils.normalizeHost(host)
            val socket = discoveryManager.openTcpSocket(
                host = cleanHost,
                port = port,
                connectTimeoutMs = 1500
            )
            socket.soTimeout = 5_000

            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
            val ephKp = CryptoEngine.generateIdentityKeyPair()
            val ephPubB64 = Base64.encodeToString(ephKp.public.encoded, Base64.NO_WRAP)
            val clientNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
            val signatureB64 = CryptoEngine.signHandshake(
                identityPrivateKey = privKey,
                roleTag = "CLIENT",
                identityPubBase64 = identity.publicKeyBase64,
                ephemeralPubBase64 = ephPubB64,
                nonceBase64 = clientNonceB64
            )

            val localIrohStatus = _irohStatus.value
            val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
            val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
            val helloBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", ephPubB64)
                put("nonce", clientNonceB64)
                put("sig", signatureB64)
                put("port", _boundPort.value)
                put("token", inviteToken)
                put("auto", isAutoReconnect)
                put("inst", localInst)
                if (localIrohId.isNotBlank()) put("irohId", localIrohId)
                if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
            }.toString().toByteArray(Charsets.UTF_8)

            out.writeByte(KIND_HANDSHAKE_HELLO.toInt())
            out.writeInt(helloBytes.size)
            out.write(helloBytes)
            out.flush()

            val respKind = inp.readByte()
            val respLen = inp.readInt()
            if (respLen < 0 || respLen > MAX_FRAME_BYTES) {
                socket.close()
                return@withContext Result.failure(SecurityException("Invalid handshake response"))
            }
            val respBytes = ByteArray(respLen)
            if (respLen > 0) inp.readFully(respBytes)

            if (respKind == KIND_HANDSHAKE_REJECT) {
                socket.close()
                val reason = runCatching {
                    JSONObject(String(respBytes, Charsets.UTF_8)).optString("reason")
                }.getOrDefault("Connection rejected by peer privacy policy")
                return@withContext Result.failure(SecurityException(reason))
            }
            if (respKind != KIND_HANDSHAKE_WELCOME) {
                socket.close()
                return@withContext Result.failure(IllegalStateException("Unexpected protocol frame on socket"))
            }

            val welcomeJson = JSONObject(String(respBytes, Charsets.UTF_8))
            val serverPubB64 = welcomeJson.getString("pub")
            val serverEphB64 = welcomeJson.getString("eph")
            val serverNonceB64 = welcomeJson.getString("nonce")
            val serverSigB64 = welcomeJson.getString("sig")
            val serverListenPort = welcomeJson.optInt("port", port)
            val serverInstanceId = welcomeJson.optString("inst", "")
            val serverIrohId = welcomeJson.optString("irohId", "")
            val serverIrohRelay = welcomeJson.optString("irohRelay", "")

            if (expectedPubKeyBase64.isNotBlank() && expectedPubKeyBase64 != serverPubB64) {
                socket.close()
                return@withContext Result.failure(
                    SecurityException("Public key mismatch against QR invite. Possible interception blocked.")
                )
            }
            if (expectedInstanceId.isNotBlank() &&
                serverInstanceId.isNotBlank() &&
                expectedInstanceId != serverInstanceId
            ) {
                socket.close()
                return@withContext Result.failure(
                    SecurityException("Peer installation ID mismatch. Scan QR invite to verify.")
                )
            }

            val validSig = CryptoEngine.verifyHandshake(
                roleTag = "SERVER",
                identityPubBase64 = serverPubB64,
                ephemeralPubBase64 = serverEphB64,
                nonceBase64 = serverNonceB64,
                signatureBase64 = serverSigB64
            )
            if (!validSig) {
                socket.close()
                return@withContext Result.failure(
                    SecurityException("Invalid ECDSA cryptographic signature during handshake")
                )
            }

            val rawPeerId = CryptoEngine.computePeerIdFromBase64(serverPubB64)
            val isSameKey = rawPeerId == identity.peerId
            if (isSameKey && serverInstanceId.isNotBlank() && serverInstanceId != localInst) {
                socket.close()
                return@withContext Result.failure(
                    SecurityException("Conflicting Chatso key detected on another installation.")
                )
            }
            val isSelfLoopback = isSameKey && (serverInstanceId.isBlank() || serverInstanceId == localInst)
            val sessionPeerId = if (isSelfLoopback) "loopback_${rawPeerId.take(12)}" else rawPeerId

            if (!isSelfLoopback) {
                val savedInst = listener.getKnownPeerInstanceId(sessionPeerId)
                if (savedInst.isNotBlank() &&
                    serverInstanceId.isNotBlank() &&
                    savedInst != serverInstanceId &&
                    inviteToken.isBlank()
                ) {
                    socket.close()
                    return@withContext Result.failure(
                        SecurityException("Contact switched app installation. Re-verify via QR invite.")
                    )
                }
            }

            val cipher = CryptoEngine.deriveSessionCipher(
                isInitiator = true,
                localEphemeralPrivate = ephKp.private,
                remoteEphemeralPubBase64 = serverEphB64,
                clientIdentityPubBase64 = identity.publicKeyBase64,
                clientEphPubBase64 = ephPubB64,
                clientNonceBase64 = clientNonceB64,
                serverIdentityPubBase64 = serverPubB64,
                serverEphPubBase64 = serverEphB64,
                serverNonceBase64 = serverNonceB64
            )

            socket.soTimeout = 0

            if (isAutoReconnect && !isSelfLoopback && !listener.isKnownPeer(sessionPeerId)) {
                socket.close()
                return@withContext Result.failure(
                    IllegalStateException("Auto-reconnect aborted for removed conversation")
                )
            }

            val session = ActivePeerSession(
                peerId = sessionPeerId,
                socket = socket,
                output = out,
                cipher = cipher,
                remotePubB64 = serverPubB64,
                remoteIp = cleanHost,
                remoteInstanceId = serverInstanceId,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay,
                isInviteAuthorized = inviteToken.isNotBlank(),
                remoteListenPort = serverListenPort,
                remoteUsername = if (isSelfLoopback) "${identity.username} (Localhost)" else cleanHost
            )
            registerActiveSession(sessionPeerId, session)

            sendProfileSync(session, identity)
            if (identity.bridgeEnabled && !isSelfLoopback) {
                broadcastBridgePeers()
            }

            listener.onPeerHandshakedWithMeta(
                peerId = sessionPeerId,
                publicKeyBase64 = serverPubB64,
                remoteIp = cleanHost,
                remoteListenPort = serverListenPort,
                username = if (isSelfLoopback) "${identity.username} (Localhost)" else cleanHost,
                avatarBase64 = null,
                viaBridgePeerId = "",
                remoteInstanceId = serverInstanceId,
                isInviteAuthorized = inviteToken.isNotBlank(),
                allowRestoreDismissed = !isAutoReconnect,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay
            )

            scope.launch(Dispatchers.IO) {
                runSessionReadLoop(
                    session = session,
                    inp = inp,
                    irohChannel = null,
                    remotePubB64 = serverPubB64,
                    remoteIp = cleanHost,
                    remoteListenPort = serverListenPort,
                    isSelfLoopbackResponder = false
                )
            }
            Result.success(sessionPeerId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun connectViaIroh(
        targetEndpointId: String,
        targetRelayUrl: String = "",
        expectedPubKeyBase64: String = "",
        inviteToken: String = "",
        isAutoReconnect: Boolean = false,
        expectedInstanceId: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            return@withContext Result.failure(IllegalStateException("Local identity is not initialized yet"))
        }
        if (identity.irohRelayMode == IrohRelayMode.DISABLED) {
            return@withContext Result.failure(IllegalStateException("Iroh relay is disabled in Settings"))
        }
        val cleanTargetId = targetEndpointId.trim()
        if (cleanTargetId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Missing Iroh Endpoint ID"))
        }
        val endpointSession = irohSession?.takeIf { !it.isClosed } ?: run {
            irohLifecycleMutex.withLock {
                ensureIrohStartedLocked(forceRestart = false)
            }
            irohSession?.takeIf { !it.isClosed }
        } ?: return@withContext Result.failure(
            IllegalStateException(_irohStatus.value.errorMessage.ifBlank { "Iroh relay is offline" })
        )

        val localEndpointId = endpointSession.endpointId.ifBlank { identity.irohEndpointId }
        if (localEndpointId.isNotBlank() && cleanTargetId == localEndpointId) {
            return@withContext Result.failure(SecurityException("Cannot connect to your own Iroh Endpoint ID"))
        }

        val cleanRelayUrl = targetRelayUrl.trim().let { raw ->
            if (raw.isEmpty()) "" else (IrohRelayUrlUtils.normalizeSingleRelayUrl(raw) ?: raw)
        }
        val localInst = effectiveInstanceId(identity)
        var channel: IrohPeerChannel? = null
        try {
            channel = endpointSession.connectPeerChannel(
                targetEndpointId = cleanTargetId,
                targetRelayUrl = cleanRelayUrl
            )
            val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
            val ephKp = CryptoEngine.generateIdentityKeyPair()
            val ephPubB64 = Base64.encodeToString(ephKp.public.encoded, Base64.NO_WRAP)
            val clientNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
            val signatureB64 = CryptoEngine.signHandshake(
                identityPrivateKey = privKey,
                roleTag = "CLIENT",
                identityPubBase64 = identity.publicKeyBase64,
                ephemeralPubBase64 = ephPubB64,
                nonceBase64 = clientNonceB64
            )
            val localRelayUrl = endpointSession.activeRelayUrl.ifBlank { configuredRelayUrlSummary(identity) }
            val helloBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", ephPubB64)
                put("nonce", clientNonceB64)
                put("sig", signatureB64)
                put("port", _boundPort.value)
                put("token", inviteToken)
                put("auto", isAutoReconnect)
                put("inst", localInst)
                if (localEndpointId.isNotBlank()) put("irohId", localEndpointId)
                if (localRelayUrl.isNotBlank()) put("irohRelay", localRelayUrl)
            }.toString().toByteArray(Charsets.UTF_8)

            channel.writeFrame(KIND_HANDSHAKE_HELLO, helloBytes)

            val response = withTimeoutOrNull(10_000L) { channel.readFrame(MAX_FRAME_BYTES) }
                ?: run {
                    channel.close()
                    return@withContext Result.failure(IllegalStateException("Timed out waiting for Iroh peer handshake"))
                }
            val (respKind, respBytes) = response
            if (respKind == KIND_HANDSHAKE_REJECT) {
                channel.close()
                val reason = runCatching {
                    JSONObject(String(respBytes, Charsets.UTF_8)).optString("reason")
                }.getOrDefault("Connection rejected by peer privacy policy")
                return@withContext Result.failure(SecurityException(reason))
            }
            if (respKind != KIND_HANDSHAKE_WELCOME) {
                channel.close()
                return@withContext Result.failure(IllegalStateException("Unexpected protocol frame on Iroh channel"))
            }

            val welcomeJson = JSONObject(String(respBytes, Charsets.UTF_8))
            val serverPubB64 = welcomeJson.getString("pub")
            val serverEphB64 = welcomeJson.getString("eph")
            val serverNonceB64 = welcomeJson.getString("nonce")
            val serverSigB64 = welcomeJson.getString("sig")
            val serverListenPort = welcomeJson.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
            val serverInstanceId = welcomeJson.optString("inst", "")
            val serverIrohId = welcomeJson.optString("irohId", "").ifBlank { cleanTargetId }
            val serverIrohRelay = welcomeJson.optString("irohRelay", "").ifBlank { cleanRelayUrl }

            if (expectedPubKeyBase64.isNotBlank() && expectedPubKeyBase64 != serverPubB64) {
                channel.close()
                return@withContext Result.failure(
                    SecurityException("Public key mismatch against QR invite. Possible interception blocked.")
                )
            }
            if (expectedInstanceId.isNotBlank() &&
                serverInstanceId.isNotBlank() &&
                expectedInstanceId != serverInstanceId
            ) {
                channel.close()
                return@withContext Result.failure(
                    SecurityException("Peer installation ID mismatch. Scan QR invite to verify.")
                )
            }

            val validSig = CryptoEngine.verifyHandshake(
                roleTag = "SERVER",
                identityPubBase64 = serverPubB64,
                ephemeralPubBase64 = serverEphB64,
                nonceBase64 = serverNonceB64,
                signatureBase64 = serverSigB64
            )
            if (!validSig) {
                channel.close()
                return@withContext Result.failure(
                    SecurityException("Invalid ECDSA cryptographic signature during Iroh handshake")
                )
            }

            val rawPeerId = CryptoEngine.computePeerIdFromBase64(serverPubB64)
            if (rawPeerId == identity.peerId || (serverInstanceId.isNotBlank() && serverInstanceId == localInst)) {
                channel.close()
                return@withContext Result.failure(
                    SecurityException("Conflicting Chatso installation identity rejected.")
                )
            }
            val savedInst = listener.getKnownPeerInstanceId(rawPeerId)
            if (savedInst.isNotBlank() &&
                serverInstanceId.isNotBlank() &&
                savedInst != serverInstanceId &&
                inviteToken.isBlank()
            ) {
                channel.close()
                return@withContext Result.failure(
                    SecurityException("Contact switched app installation. Re-verify via QR invite.")
                )
            }

            val cipher = CryptoEngine.deriveSessionCipher(
                isInitiator = true,
                localEphemeralPrivate = ephKp.private,
                remoteEphemeralPubBase64 = serverEphB64,
                clientIdentityPubBase64 = identity.publicKeyBase64,
                clientEphPubBase64 = ephPubB64,
                clientNonceBase64 = clientNonceB64,
                serverIdentityPubBase64 = serverPubB64,
                serverEphPubBase64 = serverEphB64,
                serverNonceBase64 = serverNonceB64
            )

            if (isAutoReconnect && !listener.isKnownPeer(rawPeerId)) {
                channel.close()
                return@withContext Result.failure(
                    IllegalStateException("Auto-reconnect aborted for removed conversation")
                )
            }

            val displayFallbackName = IrohRelayUrlUtils.shortEndpointId(serverIrohId)
            val session = ActivePeerSession(
                peerId = rawPeerId,
                socket = null,
                output = null,
                irohChannel = channel,
                cipher = cipher,
                remotePubB64 = serverPubB64,
                remoteIp = "",
                remoteInstanceId = serverInstanceId,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay,
                isInviteAuthorized = inviteToken.isNotBlank(),
                remoteListenPort = serverListenPort,
                remoteUsername = displayFallbackName
            )
            registerActiveSession(rawPeerId, session)

            sendProfileSync(session, identity)
            if (identity.bridgeEnabled) {
                broadcastBridgePeers()
            }

            listener.onPeerHandshakedWithMeta(
                peerId = rawPeerId,
                publicKeyBase64 = serverPubB64,
                remoteIp = "",
                remoteListenPort = serverListenPort,
                username = displayFallbackName,
                avatarBase64 = null,
                viaBridgePeerId = "",
                remoteInstanceId = serverInstanceId,
                isInviteAuthorized = inviteToken.isNotBlank(),
                allowRestoreDismissed = !isAutoReconnect,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay
            )

            scope.launch(Dispatchers.IO) {
                runSessionReadLoop(
                    session = session,
                    inp = null,
                    irohChannel = channel,
                    remotePubB64 = serverPubB64,
                    remoteIp = "",
                    remoteListenPort = serverListenPort,
                    isSelfLoopbackResponder = false
                )
            }
            Result.success(rawPeerId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            runCatching { channel?.close() }
            throw ce
        } catch (e: Exception) {
            runCatching { channel?.close() }
            Result.failure(e)
        }
    }

    suspend fun connectViaBridge(
        bridgePeerId: String,
        targetPeerId: String,
        expectedPubKeyBase64: String = "",
        inviteToken: String = "",
        isAutoReconnect: Boolean = false,
        expectedInstanceId: String = ""
    ): Result<String> = bridgedConnectMutexByPeer.computeIfAbsent(targetPeerId) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            return@withContext Result.failure(IllegalStateException("Local identity is not initialized yet"))
        }
        val existing = sessions[targetPeerId]
        if (existing != null && !existing.isClosed) {
            if (!isAutoReconnect && existing.isSilentDismissed) {
                materializeSilentSessionIfNeeded(existing)
            }
            return@withContext Result.success(targetPeerId)
        }
        val bridgeSession = sessions[bridgePeerId]
        if (bridgeSession == null || bridgeSession.isClosed || bridgeSession.bridgePeerId != null) {
            return@withContext Result.failure(IllegalStateException("Bridge peer is offline"))
        }

        val localInst = effectiveInstanceId(identity)
        val waiter = CompletableDeferred<Pair<Byte, ByteArray>>()
        pendingBridgedHandshakes[targetPeerId] = waiter
        try {
            val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
            val ephKp = CryptoEngine.generateIdentityKeyPair()
            val ephPubB64 = Base64.encodeToString(ephKp.public.encoded, Base64.NO_WRAP)
            val clientNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
            val signatureB64 = CryptoEngine.signHandshake(
                identityPrivateKey = privKey,
                roleTag = "CLIENT",
                identityPubBase64 = identity.publicKeyBase64,
                ephemeralPubBase64 = ephPubB64,
                nonceBase64 = clientNonceB64
            )

            val localIrohStatus = _irohStatus.value
            val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
            val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
            val helloBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", ephPubB64)
                put("nonce", clientNonceB64)
                put("sig", signatureB64)
                put("port", _boundPort.value)
                put("token", inviteToken)
                put("auto", isAutoReconnect)
                put("inst", localInst)
                if (localIrohId.isNotBlank()) put("irohId", localIrohId)
                if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
            }.toString().toByteArray(Charsets.UTF_8)

            val envelope = JSONObject().apply {
                put("cmd", "BRIDGE_FRAME")
                put("from", identity.peerId)
                put("to", targetPeerId)
                put("frameKind", KIND_HANDSHAKE_HELLO.toInt())
                put("data", Base64.encodeToString(helloBytes, Base64.NO_WRAP))
            }.toString().toByteArray(Charsets.UTF_8)
            bridgeSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, envelope)

            val response = withTimeoutOrNull(4_500L) { waiter.await() }
                ?: return@withContext Result.failure(IllegalStateException("Timed out connecting via bridge"))

            val (respKind, respBytes) = response
            if (respKind == KIND_BRIDGE_SESSION_READY) {
                val readySession = sessions[targetPeerId]
                return@withContext if (readySession != null && !readySession.isClosed) {
                    if (!isAutoReconnect && readySession.isSilentDismissed) {
                        materializeSilentSessionIfNeeded(readySession)
                    }
                    Result.success(targetPeerId)
                } else {
                    Result.failure(IllegalStateException("Crossed bridged handshake did not complete"))
                }
            }
            if (respKind == KIND_HANDSHAKE_REJECT) {
                val reason = runCatching {
                    JSONObject(String(respBytes, Charsets.UTF_8)).optString("reason")
                }.getOrDefault("Connection rejected via bridge")
                return@withContext Result.failure(SecurityException(reason))
            }
            if (respKind != KIND_HANDSHAKE_WELCOME) {
                return@withContext Result.failure(IllegalStateException("Unexpected bridged handshake frame"))
            }

            val welcomeJson = JSONObject(String(respBytes, Charsets.UTF_8))
            val serverPubB64 = welcomeJson.getString("pub")
            val serverEphB64 = welcomeJson.getString("eph")
            val serverNonceB64 = welcomeJson.getString("nonce")
            val serverSigB64 = welcomeJson.getString("sig")
            val serverListenPort = welcomeJson.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
            val serverInstanceId = welcomeJson.optString("inst", "")
            val serverIrohId = welcomeJson.optString("irohId", "")
            val serverIrohRelay = welcomeJson.optString("irohRelay", "")

            if (expectedPubKeyBase64.isNotBlank() && expectedPubKeyBase64 != serverPubB64) {
                return@withContext Result.failure(
                    SecurityException("Public key mismatch via bridge. Possible interception blocked.")
                )
            }
            if (expectedInstanceId.isNotBlank() &&
                serverInstanceId.isNotBlank() &&
                expectedInstanceId != serverInstanceId
            ) {
                return@withContext Result.failure(
                    SecurityException("Peer installation ID mismatch via bridge.")
                )
            }
            val computedPeerId = CryptoEngine.computePeerIdFromBase64(serverPubB64)
            if (computedPeerId != targetPeerId) {
                return@withContext Result.failure(SecurityException("Bridged peer ID mismatch"))
            }
            if (serverInstanceId.isNotBlank() && serverInstanceId == localInst) {
                return@withContext Result.failure(SecurityException("Cannot bridge to local installation"))
            }
            val savedInst = listener.getKnownPeerInstanceId(targetPeerId)
            if (savedInst.isNotBlank() &&
                serverInstanceId.isNotBlank() &&
                savedInst != serverInstanceId &&
                inviteToken.isBlank()
            ) {
                return@withContext Result.failure(
                    SecurityException("Contact switched app installation. Re-verify via QR invite.")
                )
            }

            val validSig = CryptoEngine.verifyHandshake(
                roleTag = "SERVER",
                identityPubBase64 = serverPubB64,
                ephemeralPubBase64 = serverEphB64,
                nonceBase64 = serverNonceB64,
                signatureBase64 = serverSigB64
            )
            if (!validSig) {
                return@withContext Result.failure(
                    SecurityException("Invalid ECDSA signature during bridged handshake")
                )
            }

            val cipher = CryptoEngine.deriveSessionCipher(
                isInitiator = true,
                localEphemeralPrivate = ephKp.private,
                remoteEphemeralPubBase64 = serverEphB64,
                clientIdentityPubBase64 = identity.publicKeyBase64,
                clientEphPubBase64 = ephPubB64,
                clientNonceBase64 = clientNonceB64,
                serverIdentityPubBase64 = serverPubB64,
                serverEphPubBase64 = serverEphB64,
                serverNonceBase64 = serverNonceB64
            )

            if (isAutoReconnect && !listener.isKnownPeer(targetPeerId)) {
                return@withContext Result.failure(
                    IllegalStateException("Auto-reconnect aborted for removed conversation")
                )
            }

            val bridgedSession = ActivePeerSession(
                peerId = targetPeerId,
                socket = null,
                output = null,
                cipher = cipher,
                bridgePeerId = bridgePeerId,
                remotePubB64 = serverPubB64,
                remoteIp = bridgeSession.remoteIp,
                remoteInstanceId = serverInstanceId,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay,
                isInviteAuthorized = inviteToken.isNotBlank(),
                remoteListenPort = serverListenPort,
                remoteUsername = targetPeerId
            )
            registerActiveSession(targetPeerId, bridgedSession)
            sendProfileSync(bridgedSession, identity)

            listener.onPeerHandshakedWithMeta(
                peerId = targetPeerId,
                publicKeyBase64 = serverPubB64,
                remoteIp = bridgeSession.remoteIp,
                remoteListenPort = serverListenPort,
                username = "",
                avatarBase64 = null,
                viaBridgePeerId = bridgePeerId,
                remoteInstanceId = serverInstanceId,
                isInviteAuthorized = inviteToken.isNotBlank(),
                allowRestoreDismissed = !isAutoReconnect,
                remoteIrohId = serverIrohId,
                remoteIrohRelayUrl = serverIrohRelay
            )
            Result.success(targetPeerId)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingBridgedHandshakes.remove(targetPeerId, waiter)
        }
        }
    }

    private suspend fun handleInboundSocket(socket: Socket) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            socket.close()
            return
        }
        val localInst = effectiveInstanceId(identity)
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 8_000
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val firstKind = inp.readByte()
            val firstLen = inp.readInt()
            if (firstLen < 0 || firstLen > MAX_FRAME_BYTES) {
                socket.close()
                return
            }
            val firstPayload = ByteArray(firstLen)
            if (firstLen > 0) inp.readFully(firstPayload)

            if (firstKind == DiscoveryManager.KIND_DISCOVERY_PROBE) {
                if (identity.isDiscoverable) {
                    val reply = discoveryManager.buildDiscoveryReplyBytes(
                        identity.copy(listenPort = _boundPort.value)
                    )
                    if (reply.isNotEmpty()) {
                        out.writeByte(DiscoveryManager.KIND_DISCOVERY_REPLY.toInt())
                        out.writeInt(reply.size)
                        out.write(reply)
                        out.flush()
                    }
                }
                socket.close()
                return
            }

            if (firstKind != KIND_HANDSHAKE_HELLO) {
                socket.close()
                return
            }

            val helloJson = JSONObject(String(firstPayload, Charsets.UTF_8))
            val clientPubB64 = helloJson.getString("pub")
            val clientEphB64 = helloJson.getString("eph")
            val clientNonceB64 = helloJson.getString("nonce")
            val clientSigB64 = helloJson.getString("sig")
            val clientListenPort = helloJson.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
            val inviteToken = helloJson.optString("token", "")
            val isAutoReconnect = helloJson.optBoolean("auto", false)
            val clientInstanceId = helloJson.optString("inst", "")
            val clientIrohId = helloJson.optString("irohId", "")
            val clientIrohRelay = helloJson.optString("irohRelay", "")

            val validSig = CryptoEngine.verifyHandshake(
                roleTag = "CLIENT",
                identityPubBase64 = clientPubB64,
                ephemeralPubBase64 = clientEphB64,
                nonceBase64 = clientNonceB64,
                signatureBase64 = clientSigB64
            )
            if (!validSig) {
                socket.close()
                return
            }

            val remoteIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
            val remotePeerId = CryptoEngine.computePeerIdFromBase64(clientPubB64)
            val isSameKey = remotePeerId == identity.peerId
            if (isSameKey && clientInstanceId.isNotBlank() && clientInstanceId != localInst) {
                sendRejectAndClose(out, socket, "Conflicting Chatso installation identity rejected.")
                return
            }
            val isSelfLoopback = isSameKey && (clientInstanceId.isBlank() || clientInstanceId == localInst)

            var silentDismissed = false
            var validToken = false
            if (!isSelfLoopback) {
                val isKnown = listener.isKnownPeer(remotePeerId)
                val isDismissed = listener.isPeerDismissed(remotePeerId) ||
                    (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId))
                validToken = inviteToken.isNotBlank() && inviteToken == identity.qrInviteToken

                if (clientInstanceId.isBlank() &&
                    NetworkUtils.isSameDeviceIp(remoteIp) &&
                    !isKnown &&
                    !validToken
                ) {
                    sendRejectAndClose(out, socket, "Incompatible or missing Chatso instance ID on local device.")
                    return
                }

                val knownInstId = if (isKnown) listener.getKnownPeerInstanceId(remotePeerId) else ""
                if (isKnown &&
                    knownInstId.isNotBlank() &&
                    clientInstanceId.isNotBlank() &&
                    knownInstId != clientInstanceId &&
                    !validToken
                ) {
                    sendRejectAndClose(out, socket, "Installation ID mismatch for saved contact. Scan QR invite to re-verify.")
                    return
                }

                if (isAutoReconnect && (isDismissed || (!isKnown && !validToken))) {
                    sendRejectAndClose(out, socket, "Auto-reconnect skipped for unknown or removed conversation.")
                    return
                }
                if (identity.blockUnknownPeers && !isKnown && !validToken) {
                    sendRejectAndClose(out, socket, "This device only accepts connections from saved contacts or QR invites.")
                    return
                }
                silentDismissed = isDismissed && !isKnown && !validToken
            }

            val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
            val serverEphKp = CryptoEngine.generateIdentityKeyPair()
            val serverEphB64 = Base64.encodeToString(serverEphKp.public.encoded, Base64.NO_WRAP)
            val serverNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
            val serverSigB64 = CryptoEngine.signHandshake(
                identityPrivateKey = privKey,
                roleTag = "SERVER",
                identityPubBase64 = identity.publicKeyBase64,
                ephemeralPubBase64 = serverEphB64,
                nonceBase64 = serverNonceB64
            )

            val localIrohStatus = _irohStatus.value
            val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
            val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
            val welcomeBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", serverEphB64)
                put("nonce", serverNonceB64)
                put("sig", serverSigB64)
                put("port", _boundPort.value)
                put("inst", localInst)
                if (localIrohId.isNotBlank()) put("irohId", localIrohId)
                if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
            }.toString().toByteArray(Charsets.UTF_8)

            out.writeByte(KIND_HANDSHAKE_WELCOME.toInt())
            out.writeInt(welcomeBytes.size)
            out.write(welcomeBytes)
            out.flush()

            val cipher = CryptoEngine.deriveSessionCipher(
                isInitiator = false,
                localEphemeralPrivate = serverEphKp.private,
                remoteEphemeralPubBase64 = clientEphB64,
                clientIdentityPubBase64 = clientPubB64,
                clientEphPubBase64 = clientEphB64,
                clientNonceBase64 = clientNonceB64,
                serverIdentityPubBase64 = identity.publicKeyBase64,
                serverEphPubBase64 = serverEphB64,
                serverNonceBase64 = serverNonceB64
            )

            socket.soTimeout = 0

            if (isSelfLoopback) {
                val responderSession = ActivePeerSession(
                    peerId = "loopback_srv",
                    socket = socket,
                    output = out,
                    cipher = cipher,
                    remotePubB64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteInstanceId = clientInstanceId,
                    remoteListenPort = clientListenPort,
                    remoteUsername = identity.username
                )
                runSessionReadLoop(
                    session = responderSession,
                    inp = inp,
                    irohChannel = null,
                    remotePubB64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteListenPort = clientListenPort,
                    isSelfLoopbackResponder = true
                )
            } else {
                val dismissedAfterCrypto = !validToken &&
                    !listener.isKnownPeer(remotePeerId) &&
                    (listener.isPeerDismissed(remotePeerId) ||
                        (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId)))
                if (isAutoReconnect && dismissedAfterCrypto) {
                    socket.close()
                    return
                }
                val finalSilentDismissed = silentDismissed || dismissedAfterCrypto
                val session = ActivePeerSession(
                    peerId = remotePeerId,
                    socket = socket,
                    output = out,
                    cipher = cipher,
                    remotePubB64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteInstanceId = clientInstanceId,
                    remoteIrohId = clientIrohId,
                    remoteIrohRelayUrl = clientIrohRelay,
                    isInviteAuthorized = validToken,
                    remoteListenPort = clientListenPort,
                    remoteUsername = remoteIp,
                    isSilentDismissed = finalSilentDismissed
                )
                registerActiveSession(remotePeerId, session)
                sendProfileSync(session, identity)
                if (!finalSilentDismissed) {
                    if (identity.bridgeEnabled) {
                        broadcastBridgePeers()
                    }
                    listener.onPeerHandshakedWithMeta(
                        peerId = remotePeerId,
                        publicKeyBase64 = clientPubB64,
                        remoteIp = remoteIp,
                        remoteListenPort = clientListenPort,
                        username = remoteIp,
                        avatarBase64 = null,
                        viaBridgePeerId = "",
                        remoteInstanceId = clientInstanceId,
                        isInviteAuthorized = validToken,
                        allowRestoreDismissed = validToken,
                        remoteIrohId = clientIrohId,
                        remoteIrohRelayUrl = clientIrohRelay
                    )
                }
                runSessionReadLoop(
                    session = session,
                    inp = inp,
                    irohChannel = null,
                    remotePubB64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteListenPort = clientListenPort,
                    isSelfLoopbackResponder = false
                )
            }
        } catch (_: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendIrohRejectAndClose(channel: IrohPeerChannel, reason: String) {
        runCatching {
            val rej = JSONObject().apply { put("reason", reason) }.toString().toByteArray(Charsets.UTF_8)
            channel.writeFrame(KIND_HANDSHAKE_REJECT, rej)
        }
        runCatching { channel.close() }
    }

    private suspend fun handleInboundIrohChannel(channel: IrohPeerChannel) {
        val identity = currentIdentity
        if (!identity.isInitialized || identity.irohRelayMode == IrohRelayMode.DISABLED) {
            channel.close()
            return
        }
        val localInst = effectiveInstanceId(identity)
        try {
            val firstFrame = withTimeoutOrNull(8_000L) { channel.readFrame(MAX_FRAME_BYTES) }
            if (firstFrame == null) {
                channel.close()
                return
            }
            val (firstKind, firstPayload) = firstFrame
            if (firstKind != KIND_HANDSHAKE_HELLO) {
                channel.close()
                return
            }

            val helloJson = JSONObject(String(firstPayload, Charsets.UTF_8))
            val clientPubB64 = helloJson.getString("pub")
            val clientEphB64 = helloJson.getString("eph")
            val clientNonceB64 = helloJson.getString("nonce")
            val clientSigB64 = helloJson.getString("sig")
            val clientListenPort = helloJson.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
            val inviteToken = helloJson.optString("token", "")
            val isAutoReconnect = helloJson.optBoolean("auto", false)
            val clientInstanceId = helloJson.optString("inst", "")
            val clientIrohId = helloJson.optString("irohId", "").ifBlank { channel.remoteEndpointId }
            val clientIrohRelay = helloJson.optString("irohRelay", "").ifBlank { channel.remoteRelayUrl }

            val validSig = CryptoEngine.verifyHandshake(
                roleTag = "CLIENT",
                identityPubBase64 = clientPubB64,
                ephemeralPubBase64 = clientEphB64,
                nonceBase64 = clientNonceB64,
                signatureBase64 = clientSigB64
            )
            if (!validSig) {
                channel.close()
                return
            }

            val remotePeerId = CryptoEngine.computePeerIdFromBase64(clientPubB64)
            if (remotePeerId == identity.peerId || (clientInstanceId.isNotBlank() && clientInstanceId == localInst)) {
                sendIrohRejectAndClose(channel, "Conflicting Chatso installation identity rejected.")
                return
            }

            val isKnown = listener.isKnownPeer(remotePeerId)
            val isDismissed = listener.isPeerDismissed(remotePeerId) ||
                (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId))
            val validToken = inviteToken.isNotBlank() && inviteToken == identity.qrInviteToken

            val knownInstId = if (isKnown) listener.getKnownPeerInstanceId(remotePeerId) else ""
            if (isKnown &&
                knownInstId.isNotBlank() &&
                clientInstanceId.isNotBlank() &&
                knownInstId != clientInstanceId &&
                !validToken
            ) {
                sendIrohRejectAndClose(channel, "Installation ID mismatch for saved contact. Scan QR invite to re-verify.")
                return
            }

            if (isAutoReconnect && (isDismissed || (!isKnown && !validToken))) {
                sendIrohRejectAndClose(channel, "Auto-reconnect skipped for unknown or removed conversation.")
                return
            }
            if (identity.blockUnknownPeers && !isKnown && !validToken) {
                sendIrohRejectAndClose(channel, "This device only accepts connections from saved contacts or QR invites.")
                return
            }
            val silentDismissed = isDismissed && !isKnown && !validToken

            val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
            val serverEphKp = CryptoEngine.generateIdentityKeyPair()
            val serverEphB64 = Base64.encodeToString(serverEphKp.public.encoded, Base64.NO_WRAP)
            val serverNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
            val serverSigB64 = CryptoEngine.signHandshake(
                identityPrivateKey = privKey,
                roleTag = "SERVER",
                identityPubBase64 = identity.publicKeyBase64,
                ephemeralPubBase64 = serverEphB64,
                nonceBase64 = serverNonceB64
            )
            val localIrohStatus = _irohStatus.value
            val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
            val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
            val welcomeBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", serverEphB64)
                put("nonce", serverNonceB64)
                put("sig", serverSigB64)
                put("port", _boundPort.value)
                put("inst", localInst)
                if (localIrohId.isNotBlank()) put("irohId", localIrohId)
                if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
            }.toString().toByteArray(Charsets.UTF_8)

            channel.writeFrame(KIND_HANDSHAKE_WELCOME, welcomeBytes)

            val cipher = CryptoEngine.deriveSessionCipher(
                isInitiator = false,
                localEphemeralPrivate = serverEphKp.private,
                remoteEphemeralPubBase64 = clientEphB64,
                clientIdentityPubBase64 = clientPubB64,
                clientEphPubBase64 = clientEphB64,
                clientNonceBase64 = clientNonceB64,
                serverIdentityPubBase64 = identity.publicKeyBase64,
                serverEphPubBase64 = serverEphB64,
                serverNonceBase64 = serverNonceB64
            )

            val dismissedAfterCrypto = !validToken &&
                !listener.isKnownPeer(remotePeerId) &&
                (listener.isPeerDismissed(remotePeerId) ||
                    (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId)))
            if (isAutoReconnect && dismissedAfterCrypto) {
                channel.close()
                return
            }
            val finalSilentDismissed = silentDismissed || dismissedAfterCrypto
            val displayFallbackName = IrohRelayUrlUtils.shortEndpointId(clientIrohId).ifBlank { "Iroh Peer" }
            val session = ActivePeerSession(
                peerId = remotePeerId,
                socket = null,
                output = null,
                irohChannel = channel,
                cipher = cipher,
                remotePubB64 = clientPubB64,
                remoteIp = "",
                remoteInstanceId = clientInstanceId,
                remoteIrohId = clientIrohId,
                remoteIrohRelayUrl = clientIrohRelay,
                isInviteAuthorized = validToken,
                remoteListenPort = clientListenPort,
                remoteUsername = displayFallbackName,
                isSilentDismissed = finalSilentDismissed
            )
            registerActiveSession(remotePeerId, session)
            sendProfileSync(session, identity)
            if (!finalSilentDismissed) {
                if (identity.bridgeEnabled) {
                    broadcastBridgePeers()
                }
                listener.onPeerHandshakedWithMeta(
                    peerId = remotePeerId,
                    publicKeyBase64 = clientPubB64,
                    remoteIp = "",
                    remoteListenPort = clientListenPort,
                    username = displayFallbackName,
                    avatarBase64 = null,
                    viaBridgePeerId = "",
                    remoteInstanceId = clientInstanceId,
                    isInviteAuthorized = validToken,
                    allowRestoreDismissed = validToken,
                    remoteIrohId = clientIrohId,
                    remoteIrohRelayUrl = clientIrohRelay
                )
            }
            runSessionReadLoop(
                session = session,
                inp = null,
                irohChannel = channel,
                remotePubB64 = clientPubB64,
                remoteIp = "",
                remoteListenPort = clientListenPort,
                isSelfLoopbackResponder = false
            )
        } catch (_: Exception) {
            runCatching { channel.close() }
        }
    }

    private suspend fun runSessionReadLoop(
        session: ActivePeerSession,
        inp: DataInputStream?,
        irohChannel: IrohPeerChannel?,
        remotePubB64: String,
        remoteIp: String,
        remoteListenPort: Int,
        isSelfLoopbackResponder: Boolean
    ) {
        try {
            while (scope.isActive && !session.isClosed) {
                val kind: Byte
                val encryptedPacket: ByteArray
                if (irohChannel != null) {
                    val (k, bytes) = irohChannel.readFrame(MAX_FRAME_BYTES)
                    kind = k
                    encryptedPacket = bytes
                } else {
                    val streamIn = inp ?: break
                    kind = streamIn.readByte()
                    val length = streamIn.readInt()
                    if (length <= 0 || length > MAX_FRAME_BYTES) break
                    encryptedPacket = ByteArray(length)
                    streamIn.readFully(encryptedPacket)
                }

                val plaintext = session.cipher.decrypt(kind, encryptedPacket)
                when (kind) {
                    KIND_ENCRYPTED_CONTROL -> {
                        val json = JSONObject(String(plaintext, Charsets.UTF_8))
                        handleControlPacket(
                            session = session,
                            json = json,
                            remotePubB64 = remotePubB64,
                            remoteIp = remoteIp,
                            remoteListenPort = remoteListenPort,
                            isSelfLoopbackResponder = isSelfLoopbackResponder
                        )
                    }
                    KIND_ENCRYPTED_CHUNK -> {
                        handleChunkPacket(
                            session = session,
                            plaintext = plaintext,
                            isSelfLoopbackResponder = isSelfLoopbackResponder
                        )
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (isSelfLoopbackResponder) {
                session.incomingTransfers.values.forEach { tr ->
                    synchronized(tr) {
                        tr.cancelled = true
                        runCatching { tr.outputStream.close() }
                    }
                }
                session.incomingTransfers.clear()
                session.closeQuietly()
            } else {
                cleanupSession(session)
            }
        }
    }

    private suspend fun cleanupSession(session: ActivePeerSession) {
        session.incomingTransfers.values.forEach { tr ->
            synchronized(tr) {
                tr.cancelled = true
                runCatching { tr.outputStream.close() }
            }
        }
        session.incomingTransfers.clear()
        session.closeQuietly()
        val removed = sessions.remove(session.peerId, session)
        if (removed) {
            _connectedPeers.update { it - session.peerId }
            _foregroundPeers.update { it - session.peerId }
            listener.onPeerDisconnected(session.peerId)
        }
        if (session.bridgePeerId == null) {
            discoveryManager.clearBridgedPeersFromBridge(session.peerId)
            listener.onBridgePeersUpdated(session.peerId, emptySet())
            val dependentSessions = sessions.values.filter { it.bridgePeerId == session.peerId }
            dependentSessions.forEach { bridged ->
                bridged.incomingTransfers.values.forEach { tr ->
                    synchronized(tr) {
                        tr.cancelled = true
                        runCatching { tr.outputStream.close() }
                    }
                }
                bridged.incomingTransfers.clear()
                bridged.closeQuietly()
                if (sessions.remove(bridged.peerId, bridged)) {
                    _connectedPeers.update { it - bridged.peerId }
                    _foregroundPeers.update { it - bridged.peerId }
                    listener.onPeerDisconnected(bridged.peerId)
                }
            }
            if (removed && currentIdentity.bridgeEnabled) {
                broadcastBridgePeers()
            }
        }
        refreshBridgeTelemetry()
    }

    private suspend fun handleControlPacket(
        session: ActivePeerSession,
        json: JSONObject,
        remotePubB64: String,
        remoteIp: String,
        remoteListenPort: Int,
        isSelfLoopbackResponder: Boolean
    ) {
        val cmd = json.optString("cmd")
        if (isSelfLoopbackResponder) {
            when (cmd) {
                "TEXT_MSG" -> {
                    val msgId = json.getString("id")
                    val text = json.optString("text")
                    val sdSec = json.optInt("sd", 0)
                    sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
                    sendReceiptInternal(session, msgId, DeliveryStatus.READ)
                    val echoReply = JSONObject().apply {
                        put("cmd", "TEXT_MSG")
                        put("id", java.util.UUID.randomUUID().toString())
                        put("text", "Localhost Echo (AES-256-GCM verified): $text")
                        put("sd", sdSec)
                        put("ts", System.currentTimeMillis())
                    }
                    session.sendEncryptedFrame(
                        KIND_ENCRYPTED_CONTROL,
                        echoReply.toString().toByteArray(Charsets.UTF_8)
                    )
                }
                "FILE_END" -> {
                    val msgId = json.getString("id")
                    sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
                    sendReceiptInternal(session, msgId, DeliveryStatus.READ)
                }
                "VERIFY_STATUS" -> {
                    val verified = json.optBoolean("verified", false)
                    val echoVerify = JSONObject().apply {
                        put("cmd", "VERIFY_STATUS")
                        put("verified", verified)
                    }.toString().toByteArray(Charsets.UTF_8)
                    session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, echoVerify)
                }
            }
            return
        }

        when (cmd) {
            "PROFILE_SYNC" -> {
                val name = json.optString("name", remoteIp)
                val avatarB64 = json.optString("avatar").takeIf { it.isNotBlank() }
                val listenPort = json.optInt("port", remoteListenPort)
                val isFg = json.optBoolean("fg", true)
                val isDisc = json.optBoolean("disc", true)
                val syncedInst = json.optString("inst", "")
                val syncedIrohId = json.optString("irohId", "")
                val syncedIrohRelay = json.optString("irohRelay", "")
                if (syncedInst.isNotBlank() &&
                    session.remoteInstanceId.isNotBlank() &&
                    syncedInst != session.remoteInstanceId &&
                    !session.isInviteAuthorized
                ) {
                    return
                }
                if (name.isNotBlank() && name != remoteIp) {
                    session.remoteUsername = name
                }
                if (!avatarB64.isNullOrBlank()) {
                    session.remoteAvatarBase64 = avatarB64
                }
                if (syncedIrohId.isNotBlank()) {
                    session.remoteIrohId = syncedIrohId
                }
                if (syncedIrohRelay.isNotBlank()) {
                    session.remoteIrohRelayUrl = syncedIrohRelay
                }
                session.remoteListenPort = listenPort
                session.remoteDiscoverable = isDisc
                if (!session.isSilentDismissed) {
                    _foregroundPeers.update { if (isFg) it + session.peerId else it - session.peerId }
                    listener.onPeerForegroundChanged(session.peerId, isFg)
                    listener.onPeerProfileUpdatedWithMeta(
                        peerId = session.peerId,
                        publicKeyBase64 = remotePubB64,
                        remoteIp = remoteIp,
                        remoteListenPort = listenPort,
                        username = name,
                        avatarBase64 = avatarB64,
                        viaBridgePeerId = session.bridgePeerId ?: "",
                        remoteInstanceId = syncedInst.ifBlank { session.remoteInstanceId },
                        remoteIrohId = syncedIrohId.ifBlank { session.remoteIrohId },
                        remoteIrohRelayUrl = syncedIrohRelay.ifBlank { session.remoteIrohRelayUrl }
                    )
                    if (json.has("verifiedPeer")) {
                        listener.onPeerVerificationChanged(session.peerId, json.optBoolean("verifiedPeer", false))
                    }
                    if (session.bridgePeerId == null && currentIdentity.bridgeEnabled) {
                        broadcastBridgePeers()
                    }
                }
                refreshBridgeTelemetry()
            }
            "BRIDGE_PEERS" -> {
                if (session.bridgePeerId == null) {
                    val myInst = effectiveInstanceId()
                    val bridgeName = json.optString("bridgeName").ifBlank {
                        session.remoteUsername.ifBlank { remoteIp }
                    }
                    val peersArr = json.optJSONArray("peers")
                    val list = buildList {
                        if (peersArr != null) {
                            for (i in 0 until peersArr.length()) {
                                val item = peersArr.optJSONObject(i) ?: continue
                                if (item.has("disc") && !item.optBoolean("disc", true)) continue
                                val pub = item.optString("pub")
                                if (pub.isBlank()) continue
                                val inst = item.optString("inst", "")
                                if (inst.isNotBlank() && inst == myInst) continue
                                val pId = CryptoEngine.computePeerIdFromBase64(pub)
                                if (pId == currentIdentity.peerId || pId == session.peerId) continue
                                add(
                                    DiscoveryManager.BridgedPeerInfo(
                                        peerId = pId,
                                        username = item.optString("name", "Chatso User"),
                                        publicKeyBase64 = pub,
                                        listenPort = item.optInt("port", LocalIdentity.DEFAULT_TCP_PORT),
                                        isDiscoverable = true,
                                        instanceId = inst
                                    )
                                )
                            }
                        }
                    }
                    discoveryManager.updateBridgedPeers(
                        bridgePeerId = session.peerId,
                        bridgeName = bridgeName,
                        bridgeHost = remoteIp,
                        bridgePort = session.remoteListenPort,
                        entries = list
                    )
                    listener.onBridgePeersUpdated(session.peerId, list.map { it.peerId }.toSet())
                }
            }
            "BRIDGE_FRAME" -> {
                if (session.bridgePeerId == null) {
                    handleBridgeFramePacket(session, json)
                }
            }
            "BRIDGE_PEER_UNREACHABLE" -> {
                if (session.bridgePeerId == null) {
                    val targetPeerId = json.optString("peerId")
                    if (targetPeerId.isNotBlank()) {
                        val bridgedSession = sessions[targetPeerId]?.takeIf { it.bridgePeerId == session.peerId }
                        if (bridgedSession != null) {
                            cleanupSession(bridgedSession)
                        }
                    }
                }
            }
            "VERIFY_STATUS" -> {
                if (!session.isSilentDismissed) {
                    listener.onPeerVerificationChanged(session.peerId, json.optBoolean("verified", false))
                }
            }
            "APP_STATE" -> {
                val isFg = json.optBoolean("fg", false)
                if (!session.isSilentDismissed) {
                    _foregroundPeers.update { if (isFg) it + session.peerId else it - session.peerId }
                    listener.onPeerForegroundChanged(session.peerId, isFg)
                }
            }
            "TEXT_MSG" -> {
                materializeSilentSessionIfNeeded(session)
                val msgId = json.getString("id")
                val text = json.optString("text", "")
                val sdSec = json.optInt("sd", 0)
                val ts = json.optLong("ts", System.currentTimeMillis())
                val replyId = json.optString("replyId").takeIf { it.isNotBlank() }
                val replySenderTag = invertWireAuthorType(json.optString("replyAuthorType"))
                val replyPreview = json.optString("replyPreview").takeIf { it.isNotBlank() }
                listener.onIncomingTextMessage(
                    peerId = session.peerId,
                    messageId = msgId,
                    text = text,
                    selfDestructSec = sdSec,
                    createdAt = ts,
                    replyToMessageId = replyId,
                    replyToSenderTag = replySenderTag,
                    replyToPreview = replyPreview
                )
                val sentAck = runCatching {
                    sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
                    true
                }.getOrDefault(false)
                if (!sentAck) {
                    listener.onReceiptSendFailed(session.peerId, msgId, DeliveryStatus.DELIVERED)
                }
            }
            "REACTION" -> {
                if (!session.isSilentDismissed) {
                    val targetId = json.optString("targetId")
                    val emoji = json.optString("emoji", "")
                    if (targetId.isNotBlank()) {
                        listener.onIncomingReaction(session.peerId, targetId, emoji)
                    }
                }
            }
            "TYPING" -> {
                if (!session.isSilentDismissed) {
                    listener.onTypingChanged(session.peerId, json.optBoolean("typing", false))
                }
            }
            "RECEIPT" -> {
                val msgId = json.getString("id")
                val statusStr = json.optString("status", DeliveryStatus.DELIVERED.name)
                val status = DeliveryStatus.entries.firstOrNull { it.name == statusStr }
                    ?: DeliveryStatus.DELIVERED
                listener.onDeliveryReceipt(session.peerId, msgId, status)
            }
            "RECEIPT_QUERY" -> {
                if (!session.isSilentDismissed) {
                    val idsArr = json.optJSONArray("ids")
                    if (idsArr != null) {
                        val ids = buildList {
                            for (i in 0 until idsArr.length()) {
                                val id = idsArr.optString(i)
                                if (id.isNotBlank()) add(id)
                            }
                        }
                        if (ids.isNotEmpty()) {
                            listener.onReceiptQueryReceived(session.peerId, ids)
                        }
                    }
                }
            }
            "RECEIPT_BATCH" -> {
                if (!session.isSilentDismissed) {
                    val itemsArr = json.optJSONArray("items")
                    if (itemsArr != null) {
                        val receipts = buildList {
                            for (i in 0 until itemsArr.length()) {
                                val item = itemsArr.optJSONObject(i) ?: continue
                                val id = item.optString("id")
                                val statusStr = item.optString("status", DeliveryStatus.DELIVERED.name)
                                val status = DeliveryStatus.entries.firstOrNull { it.name == statusStr }
                                    ?: continue
                                if (id.isNotBlank()) add(id to status)
                            }
                        }
                        if (receipts.isNotEmpty()) {
                            listener.onReceiptBatchReceived(session.peerId, receipts)
                        }
                    }
                }
            }
            "SELF_DESTRUCT_CONFIG" -> {
                if (!session.isSilentDismissed) {
                    listener.onSelfDestructConfigChanged(session.peerId, json.optInt("sd", 0))
                }
            }
            "FILE_START" -> {
                materializeSilentSessionIfNeeded(session)
                val msgId = json.getString("id")
                session.cancelledIncomingIds.remove(msgId)
                val typeStr = json.optString("msgType", MessageType.FILE.name)
                val msgType = MessageType.entries.firstOrNull { it.name == typeStr } ?: MessageType.FILE
                val fileName = json.optString("fileName", "file")
                val mimeType = json.optString("mimeType", "application/octet-stream")
                val totalBytes = json.optLong("totalBytes", 0L)
                val audioDur = json.optLong("audioMs", 0L)
                val sdSec = json.optInt("sd", 0)
                val ts = json.optLong("ts", System.currentTimeMillis())
                val replyId = json.optString("replyId").takeIf { it.isNotBlank() }
                val replySenderTag = invertWireAuthorType(json.optString("replyAuthorType"))
                val replyPreview = json.optString("replyPreview").takeIf { it.isNotBlank() }
                val mediaW = json.optInt("mediaW", 0).coerceAtLeast(0)
                val mediaH = json.optInt("mediaH", 0).coerceAtLeast(0)

                val outFile = listener.onIncomingFileStart(
                    peerId = session.peerId,
                    messageId = msgId,
                    messageType = msgType,
                    fileName = fileName,
                    mimeType = mimeType,
                    totalBytes = totalBytes,
                    audioDurationMs = audioDur,
                    selfDestructSec = sdSec,
                    createdAt = ts,
                    replyToMessageId = replyId,
                    replyToSenderTag = replySenderTag,
                    replyToPreview = replyPreview,
                    mediaWidth = mediaW,
                    mediaHeight = mediaH
                )
                session.incomingTransfers[msgId] = IncomingTransfer(
                    file = outFile,
                    outputStream = FileOutputStream(outFile),
                    totalBytes = totalBytes.coerceAtLeast(1L)
                )
            }
            "FILE_END" -> {
                val msgId = json.getString("id")
                val transfer = session.incomingTransfers.remove(msgId)
                val wasCancelled = session.cancelledIncomingIds.remove(msgId)
                if (transfer != null) {
                    val skipped = synchronized(transfer) {
                        val isCancelledNow = transfer.cancelled || wasCancelled
                        runCatching { transfer.outputStream.flush() }
                        runCatching { transfer.outputStream.close() }
                        isCancelledNow
                    }
                    if (!skipped) {
                        listener.onIncomingFileComplete(session.peerId, msgId, transfer.file)
                        val sentAck = runCatching {
                            sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
                            true
                        }.getOrDefault(false)
                        if (!sentAck) {
                            listener.onReceiptSendFailed(session.peerId, msgId, DeliveryStatus.DELIVERED)
                        }
                    } else {
                        runCatching { if (transfer.file.exists()) transfer.file.delete() }
                    }
                }
            }
            "FILE_CANCEL" -> {
                val msgId = json.optString("id")
                val bySender = json.optBoolean("bySender", true)
                if (msgId.isNotBlank()) {
                    if (bySender) {
                        session.cancelledIncomingIds.add(msgId)
                        val transfer = session.incomingTransfers.remove(msgId)
                        if (transfer != null) {
                            synchronized(transfer) {
                                transfer.cancelled = true
                                runCatching { transfer.outputStream.close() }
                            }
                            runCatching { if (transfer.file.exists()) transfer.file.delete() }
                        }
                        listener.onIncomingFileCancelledBySender(session.peerId, msgId)
                    } else {
                        listener.onOutgoingFileCancelledByReceiver(session.peerId, msgId)
                    }
                }
            }
            "FILE_RETRY" -> {
                if (!session.isSilentDismissed) {
                    val msgId = json.optString("id")
                    if (msgId.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            listener.onPeerRequestedFileRetry(session.peerId, msgId)
                        }
                    }
                }
            }
            "FILE_UNAVAILABLE" -> {
                if (!session.isSilentDismissed) {
                    val msgId = json.optString("id")
                    if (msgId.isNotBlank()) {
                        listener.onRemoteFileUnavailable(session.peerId, msgId)
                    }
                }
            }
        }
    }

    private suspend fun notifyBridgePeerUnreachable(
        requesterSession: ActivePeerSession,
        targetPeerId: String
    ) {
        val payload = JSONObject().apply {
            put("cmd", "BRIDGE_PEER_UNREACHABLE")
            put("peerId", targetPeerId)
        }.toString().toByteArray(Charsets.UTF_8)
        runCatching { requesterSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload) }
    }

    private suspend fun handleBridgeFramePacket(
        bridgeSession: ActivePeerSession,
        json: JSONObject
    ) {
        val identity = currentIdentity
        if (!identity.isInitialized) return
        val toPeerId = json.optString("to")
        val fromPeerId = json.optString("from")
        val frameKindInt = json.optInt("frameKind", -1)
        val dataB64 = json.optString("data")
        if (toPeerId.isBlank() || dataB64.isBlank() || frameKindInt < 0) return
        val frameKind = frameKindInt.toByte()
        val rawBytes = runCatching { Base64.decode(dataB64, Base64.NO_WRAP) }.getOrNull() ?: return

        if (toPeerId != identity.peerId) {
            // Relay mode (node B): forward encrypted frame between peers without inspecting payload
            if (!identity.bridgeEnabled || bridgeSession.peerId.startsWith("loopback_")) {
                bridgeFailedRelays.incrementAndGet()
                refreshBridgeTelemetry()
                if (frameKind == KIND_HANDSHAKE_HELLO) {
                    sendBridgeHandshakeReject(bridgeSession, toPeerId, frameKind, "Bridge mode is disabled")
                } else {
                    notifyBridgePeerUnreachable(bridgeSession, toPeerId)
                }
                return
            }
            var targetSession = sessions[toPeerId]?.takeIf {
                !it.isClosed && it.bridgePeerId == null && !it.peerId.startsWith("loopback_")
            }
            if (targetSession == null) {
                val isAutoBridgeHello = frameKind == KIND_HANDSHAKE_HELLO &&
                    runCatching {
                        JSONObject(String(rawBytes, Charsets.UTF_8)).optBoolean("auto", false)
                    }.getOrDefault(false)
                if (listener.ensureBridgeTargetConnected(toPeerId, isAutoReconnect = isAutoBridgeHello)) {
                    targetSession = sessions[toPeerId]?.takeIf {
                        !it.isClosed && it.bridgePeerId == null && !it.peerId.startsWith("loopback_")
                    }
                }
            }
            if (targetSession == null) {
                bridgeFailedRelays.incrementAndGet()
                refreshBridgeTelemetry()
                if (frameKind == KIND_HANDSHAKE_HELLO) {
                    sendBridgeHandshakeReject(bridgeSession, toPeerId, frameKind, "Target peer offline on bridge")
                } else {
                    notifyBridgePeerUnreachable(bridgeSession, toPeerId)
                }
                return
            }
            val forwardEnvelope = JSONObject().apply {
                put("cmd", "BRIDGE_FRAME")
                put("from", bridgeSession.peerId)
                put("to", toPeerId)
                put("frameKind", frameKind.toInt())
                put("data", dataB64)
            }.toString().toByteArray(Charsets.UTF_8)
            val sent = runCatching {
                targetSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, forwardEnvelope)
                true
            }.getOrDefault(false)
            if (!sent) {
                bridgeFailedRelays.incrementAndGet()
                cleanupSession(targetSession)
                refreshBridgeTelemetry()
                if (frameKind == KIND_HANDSHAKE_HELLO) {
                    sendBridgeHandshakeReject(bridgeSession, toPeerId, frameKind, "Target peer unreachable on bridge")
                } else {
                    notifyBridgePeerUnreachable(bridgeSession, toPeerId)
                }
            } else {
                val frameBytes = rawBytes.size.toLong()
                bridgeRelayedFrames.incrementAndGet()
                bridgeRelayedBytes.addAndGet(frameBytes)
                val routeKey = "${bridgeSession.peerId}|$toPeerId"
                val fromName = bridgeSession.remoteUsername.ifBlank {
                    bridgeSession.remoteIp.ifBlank { bridgeSession.peerId.take(8) }
                }
                val toName = targetSession.remoteUsername.ifBlank {
                    targetSession.remoteIp.ifBlank { toPeerId.take(8) }
                }
                val nowMs = System.currentTimeMillis()
                bridgeRouteStats.compute(routeKey) { _, prev ->
                    if (prev == null) {
                        BridgeRouteInfo(
                            fromPeerId = bridgeSession.peerId,
                            fromName = fromName,
                            toPeerId = toPeerId,
                            toName = toName,
                            relayedFrames = 1L,
                            relayedBytes = frameBytes,
                            lastRelayedAt = nowMs
                        )
                    } else {
                        prev.copy(
                            fromName = fromName,
                            toName = toName,
                            relayedFrames = prev.relayedFrames + 1L,
                            relayedBytes = prev.relayedBytes + frameBytes,
                            lastRelayedAt = nowMs
                        )
                    }
                }
                refreshBridgeTelemetry()
            }
            return
        }

        // Endpoint mode (node A or C): handle frame addressed to us via bridgeSession
        val senderPeerId = fromPeerId
        if (senderPeerId.isBlank() || senderPeerId == identity.peerId || senderPeerId == bridgeSession.peerId) return

        when (frameKind) {
            KIND_HANDSHAKE_HELLO -> handleIncomingBridgedHandshake(
                bridgeSession = bridgeSession,
                senderPeerId = senderPeerId,
                helloBytes = rawBytes
            )
            KIND_HANDSHAKE_WELCOME,
            KIND_HANDSHAKE_REJECT -> {
                pendingBridgedHandshakes[senderPeerId]?.complete(frameKind to rawBytes)
            }
            KIND_ENCRYPTED_CONTROL,
            KIND_ENCRYPTED_CHUNK -> {
                val virtualSession = sessions[senderPeerId]
                    ?.takeIf { it.bridgePeerId == bridgeSession.peerId && !it.isClosed }
                    ?: return
                val plaintext = runCatching {
                    virtualSession.cipher.decrypt(frameKind, rawBytes)
                }.getOrNull() ?: return
                if (frameKind == KIND_ENCRYPTED_CONTROL) {
                    val innerJson = runCatching {
                        JSONObject(String(plaintext, Charsets.UTF_8))
                    }.getOrNull() ?: return
                    handleControlPacket(
                        session = virtualSession,
                        json = innerJson,
                        remotePubB64 = virtualSession.remotePubB64,
                        remoteIp = virtualSession.remoteIp,
                        remoteListenPort = virtualSession.remoteListenPort,
                        isSelfLoopbackResponder = false
                    )
                } else {
                    handleChunkPacket(
                        session = virtualSession,
                        plaintext = plaintext,
                        isSelfLoopbackResponder = false
                    )
                }
            }
        }
    }

    private suspend fun sendBridgeHandshakeReject(
        requesterSession: ActivePeerSession,
        targetPeerId: String,
        originalFrameKind: Byte,
        reason: String
    ) {
        if (originalFrameKind != KIND_HANDSHAKE_HELLO) return
        val rej = JSONObject().apply { put("reason", reason) }.toString().toByteArray(Charsets.UTF_8)
        val rejEnvelope = JSONObject().apply {
            put("cmd", "BRIDGE_FRAME")
            put("from", targetPeerId)
            put("to", requesterSession.peerId)
            put("frameKind", KIND_HANDSHAKE_REJECT.toInt())
            put("data", Base64.encodeToString(rej, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
        runCatching { requesterSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, rejEnvelope) }
    }

    private suspend fun sendBridgeRejectToPeer(
        bridgeSession: ActivePeerSession,
        identity: LocalIdentity,
        targetPeerId: String,
        reason: String
    ) {
        val rej = JSONObject().apply { put("reason", reason) }.toString().toByteArray(Charsets.UTF_8)
        val rejEnvelope = JSONObject().apply {
            put("cmd", "BRIDGE_FRAME")
            put("from", identity.peerId)
            put("to", targetPeerId)
            put("frameKind", KIND_HANDSHAKE_REJECT.toInt())
            put("data", Base64.encodeToString(rej, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
        runCatching { bridgeSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, rejEnvelope) }
    }

    private suspend fun handleIncomingBridgedHandshake(
        bridgeSession: ActivePeerSession,
        senderPeerId: String,
        helloBytes: ByteArray
    ) {
        val identity = currentIdentity
        if (!identity.isInitialized) return
        val localInst = effectiveInstanceId(identity)
        val helloJson = runCatching { JSONObject(String(helloBytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val clientPubB64 = helloJson.optString("pub")
        val clientEphB64 = helloJson.optString("eph")
        val clientNonceB64 = helloJson.optString("nonce")
        val clientSigB64 = helloJson.optString("sig")
        val clientListenPort = helloJson.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
        val inviteToken = helloJson.optString("token", "")
        val isAutoReconnect = helloJson.optBoolean("auto", false)
        val clientInstanceId = helloJson.optString("inst", "")
        val clientIrohId = helloJson.optString("irohId", "")
        val clientIrohRelay = helloJson.optString("irohRelay", "")
        if (clientPubB64.isBlank() || clientEphB64.isBlank() || clientNonceB64.isBlank() || clientSigB64.isBlank()) {
            return
        }

        val validSig = CryptoEngine.verifyHandshake(
            roleTag = "CLIENT",
            identityPubBase64 = clientPubB64,
            ephemeralPubBase64 = clientEphB64,
            nonceBase64 = clientNonceB64,
            signatureBase64 = clientSigB64
        )
        if (!validSig) return

        val computedPeerId = CryptoEngine.computePeerIdFromBase64(clientPubB64)
        if (computedPeerId != senderPeerId) return

        if (computedPeerId == identity.peerId ||
            (clientInstanceId.isNotBlank() && clientInstanceId == localInst)
        ) {
            sendBridgeRejectToPeer(bridgeSession, identity, senderPeerId, "Conflicting Chatso installation identity rejected.")
            return
        }

        val isKnown = listener.isKnownPeer(computedPeerId)
        val isDismissed = listener.isPeerDismissed(computedPeerId) ||
            (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId))
        val validToken = inviteToken.isNotBlank() && inviteToken == identity.qrInviteToken

        val knownInstId = if (isKnown) listener.getKnownPeerInstanceId(computedPeerId) else ""
        if (isKnown &&
            knownInstId.isNotBlank() &&
            clientInstanceId.isNotBlank() &&
            knownInstId != clientInstanceId &&
            !validToken
        ) {
            sendBridgeRejectToPeer(
                bridgeSession,
                identity,
                senderPeerId,
                "Installation ID mismatch for saved contact. Scan QR invite to re-verify."
            )
            return
        }

        if ((isAutoReconnect && (isDismissed || (!isKnown && !validToken))) ||
            (identity.blockUnknownPeers && !isKnown && !validToken)
        ) {
            val reason = if (isAutoReconnect && !identity.blockUnknownPeers) {
                "Auto-reconnect skipped for unknown or removed conversation."
            } else {
                "This device only accepts connections from saved contacts or QR invites."
            }
            sendBridgeRejectToPeer(bridgeSession, identity, senderPeerId, reason)
            return
        }
        val silentDismissed = isDismissed && !isKnown && !validToken

        val ourWaiter = pendingBridgedHandshakes[senderPeerId]
        if (ourWaiter != null && !ourWaiter.isCompleted && identity.peerId < senderPeerId) {
            // Simultaneous crossed bridge handshake: lexicographically smaller peerId wins as initiator.
            return
        }

        val privKey = CryptoEngine.unwrapPrivateKey(identity.wrappedPrivateKeyBase64)
        val serverEphKp = CryptoEngine.generateIdentityKeyPair()
        val serverEphB64 = Base64.encodeToString(serverEphKp.public.encoded, Base64.NO_WRAP)
        val serverNonceB64 = Base64.encodeToString(CryptoEngine.randomBytes(16), Base64.NO_WRAP)
        val serverSigB64 = CryptoEngine.signHandshake(
            identityPrivateKey = privKey,
            roleTag = "SERVER",
            identityPubBase64 = identity.publicKeyBase64,
            ephemeralPubBase64 = serverEphB64,
            nonceBase64 = serverNonceB64
        )

        val localIrohStatus = _irohStatus.value
        val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
        val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
        val welcomeBytes = JSONObject().apply {
            put("pub", identity.publicKeyBase64)
            put("eph", serverEphB64)
            put("nonce", serverNonceB64)
            put("sig", serverSigB64)
            put("port", _boundPort.value)
            put("inst", localInst)
            if (localIrohId.isNotBlank()) put("irohId", localIrohId)
            if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
        }.toString().toByteArray(Charsets.UTF_8)

        val welcomeEnvelope = JSONObject().apply {
            put("cmd", "BRIDGE_FRAME")
            put("from", identity.peerId)
            put("to", senderPeerId)
            put("frameKind", KIND_HANDSHAKE_WELCOME.toInt())
            put("data", Base64.encodeToString(welcomeBytes, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)

        val cipher = CryptoEngine.deriveSessionCipher(
            isInitiator = false,
            localEphemeralPrivate = serverEphKp.private,
            remoteEphemeralPubBase64 = clientEphB64,
            clientIdentityPubBase64 = clientPubB64,
            clientEphPubBase64 = clientEphB64,
            clientNonceBase64 = clientNonceB64,
            serverIdentityPubBase64 = identity.publicKeyBase64,
            serverEphPubBase64 = serverEphB64,
            serverNonceBase64 = serverNonceB64
        )

        val dismissedAfterCrypto = !validToken &&
            !listener.isKnownPeer(computedPeerId) &&
            (listener.isPeerDismissed(computedPeerId) ||
                (clientInstanceId.isNotBlank() && listener.isInstanceDismissed(clientInstanceId)))
        if (isAutoReconnect && dismissedAfterCrypto) {
            return
        }
        val finalSilentDismissed = silentDismissed || dismissedAfterCrypto

        val virtualSession = ActivePeerSession(
            peerId = senderPeerId,
            socket = null,
            output = null,
            cipher = cipher,
            bridgePeerId = bridgeSession.peerId,
            remotePubB64 = clientPubB64,
            remoteIp = bridgeSession.remoteIp,
            remoteInstanceId = clientInstanceId,
            remoteIrohId = clientIrohId,
            remoteIrohRelayUrl = clientIrohRelay,
            isInviteAuthorized = validToken,
            remoteListenPort = clientListenPort,
            remoteUsername = senderPeerId,
            isSilentDismissed = finalSilentDismissed
        )
        registerActiveSession(senderPeerId, virtualSession)

        val sentWelcome = runCatching {
            bridgeSession.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, welcomeEnvelope)
            true
        }.getOrDefault(false)
        if (!sentWelcome) {
            cleanupSession(virtualSession)
            return
        }
        sendProfileSync(virtualSession, identity)

        val currentWaiter = pendingBridgedHandshakes[senderPeerId]
        if (currentWaiter != null && !currentWaiter.isCompleted && identity.peerId > senderPeerId) {
            currentWaiter.complete(KIND_BRIDGE_SESSION_READY to ByteArray(0))
        }

        if (!finalSilentDismissed) {
            listener.onPeerHandshakedWithMeta(
                peerId = senderPeerId,
                publicKeyBase64 = clientPubB64,
                remoteIp = bridgeSession.remoteIp,
                remoteListenPort = clientListenPort,
                username = "",
                avatarBase64 = null,
                viaBridgePeerId = bridgeSession.peerId,
                remoteInstanceId = clientInstanceId,
                isInviteAuthorized = validToken,
                allowRestoreDismissed = validToken,
                remoteIrohId = clientIrohId,
                remoteIrohRelayUrl = clientIrohRelay
            )
        }
    }

    private fun invertWireAuthorType(wireType: String?): String? {
        return when (wireType) {
            "ME" -> ReplySenderTags.PEER
            "PEER" -> ReplySenderTags.ME
            else -> null
        }
    }

    private suspend fun handleChunkPacket(
        session: ActivePeerSession,
        plaintext: ByteArray,
        isSelfLoopbackResponder: Boolean
    ) {
        if (isSelfLoopbackResponder) return
        if (plaintext.size <= 40) return
        val msgId = String(plaintext, 0, 36, Charsets.US_ASCII).trim()
        if (session.cancelledIncomingIds.contains(msgId)) return
        val transfer = session.incomingTransfers[msgId] ?: return
        val dataLen = plaintext.size - 40
        val reportProgress: Int? = synchronized(transfer) {
            if (transfer.cancelled) return@synchronized null
            transfer.outputStream.write(plaintext, 40, dataLen)
            transfer.receivedBytes += dataLen
            val progress = ((transfer.receivedBytes * 100L) / transfer.totalBytes)
                .toInt()
                .coerceIn(1, 99)
            if (progress != transfer.lastReportedProgress) {
                transfer.lastReportedProgress = progress
                progress
            } else {
                null
            }
        }
        if (reportProgress != null && !session.cancelledIncomingIds.contains(msgId)) {
            listener.onIncomingFileProgress(session.peerId, msgId, reportProgress)
        }
    }

    private suspend fun materializeSilentSessionIfNeeded(session: ActivePeerSession) {
        if (!session.isSilentDismissed) return
        session.isSilentDismissed = false
        _connectedPeers.update { it + session.peerId }
        listener.onPeerHandshakedWithMeta(
            peerId = session.peerId,
            publicKeyBase64 = session.remotePubB64,
            remoteIp = session.remoteIp,
            remoteListenPort = session.remoteListenPort,
            username = session.remoteUsername,
            avatarBase64 = session.remoteAvatarBase64,
            viaBridgePeerId = session.bridgePeerId ?: "",
            remoteInstanceId = session.remoteInstanceId,
            isInviteAuthorized = session.isInviteAuthorized,
            allowRestoreDismissed = true,
            remoteIrohId = session.remoteIrohId,
            remoteIrohRelayUrl = session.remoteIrohRelayUrl
        )
        refreshBridgeTelemetry()
    }

    private fun registerActiveSession(peerId: String, session: ActivePeerSession) {
        val old = sessions.put(peerId, session)
        old?.closeQuietly()
        if (session.isSilentDismissed) {
            _connectedPeers.update { it - peerId }
            _foregroundPeers.update { it - peerId }
        } else {
            _connectedPeers.update { it + peerId }
        }
        refreshBridgeTelemetry()
    }

    private fun refreshBridgeTelemetry() {
        val identity = currentIdentity
        val directSessions = sessions.values.filter {
            it.bridgePeerId == null && !it.isClosed && !it.isSilentDismissed && !it.peerId.startsWith("loopback_")
        }
        val directNames = directSessions.map { s ->
            s.remoteUsername.ifBlank { s.remoteIp.ifBlank { s.peerId.take(10) } }
        }.sorted()
        val advertisedNames = if (identity.bridgeEnabled) {
            directSessions.filter { it.remoteDiscoverable && it.remotePubB64.isNotBlank() }
                .map { s -> s.remoteUsername.ifBlank { s.remoteIp.ifBlank { s.peerId.take(10) } } }
                .sorted()
        } else {
            emptyList()
        }
        val routes = if (identity.bridgeEnabled) {
            bridgeRouteStats.values.sortedByDescending { it.lastRelayedAt }.take(12)
        } else {
            emptyList()
        }
        _bridgeTelemetry.value = BridgeTelemetry(
            isEnabled = identity.bridgeEnabled,
            directSessionCount = directSessions.size,
            directPeerNames = directNames,
            advertisedPeerCount = advertisedNames.size,
            advertisedPeerNames = advertisedNames,
            relayedFramesTotal = bridgeRelayedFrames.get(),
            relayedBytesTotal = bridgeRelayedBytes.get(),
            failedRelaysTotal = bridgeFailedRelays.get(),
            activeRoutes = routes
        )
    }

    fun resetBridgeTelemetry() {
        bridgeRelayedFrames.set(0L)
        bridgeRelayedBytes.set(0L)
        bridgeFailedRelays.set(0L)
        bridgeRouteStats.clear()
        refreshBridgeTelemetry()
    }

    fun getActiveDirectEndpoints(): List<Pair<String, Int>> {
        return sessions.values
            .filter {
                it.bridgePeerId == null &&
                    !it.isClosed &&
                    !it.peerId.startsWith("loopback_") &&
                    it.remoteIp.isNotBlank() &&
                    it.remoteListenPort > 0
            }
            .map { it.remoteIp to it.remoteListenPort }
    }

    private suspend fun sendProfileSync(session: ActivePeerSession, identity: LocalIdentity) {
        val avatarB64 = localAvatarBase64Provider()
        val verifiedPeer = listener.isPeerVerifiedByMe(session.peerId)
        val localIrohStatus = _irohStatus.value
        val localIrohId = localIrohStatus.endpointId.ifBlank { identity.irohEndpointId }
        val localIrohRelay = localIrohStatus.activeRelayUrl.ifBlank { localIrohStatus.configuredRelayUrl }
        val syncJson = JSONObject().apply {
            put("cmd", "PROFILE_SYNC")
            put("name", identity.username)
            put("port", _boundPort.value)
            put("fg", localAppInForeground)
            put("disc", identity.isDiscoverable)
            put("inst", effectiveInstanceId(identity))
            put("verifiedPeer", verifiedPeer)
            if (localIrohId.isNotBlank()) put("irohId", localIrohId)
            if (localIrohRelay.isNotBlank()) put("irohRelay", localIrohRelay)
            if (!avatarB64.isNullOrBlank()) put("avatar", avatarB64)
        }
        session.sendEncryptedFrame(
            KIND_ENCRYPTED_CONTROL,
            syncJson.toString().toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun broadcastProfileSync() {
        val identity = currentIdentity
        if (!identity.isInitialized) return
        for (session in sessions.values) {
            if (session.isSilentDismissed) continue
            runCatching { sendProfileSync(session, identity) }
        }
    }

    private suspend fun broadcastBridgePeers() {
        val identity = currentIdentity
        if (!identity.isInitialized) return
        val myInst = effectiveInstanceId(identity)
        val directSessions = sessions.values.filter {
            it.bridgePeerId == null && !it.isClosed && !it.isSilentDismissed && !it.peerId.startsWith("loopback_")
        }
        for (recipient in directSessions) {
            val peersArray = JSONArray()
            if (identity.bridgeEnabled) {
                directSessions
                    .filter {
                        it.peerId != recipient.peerId &&
                            (it.remoteInstanceId.isBlank() || it.remoteInstanceId != myInst) &&
                            it.remoteDiscoverable &&
                            it.remotePubB64.isNotBlank()
                    }
                    .forEach { other ->
                        peersArray.put(
                            JSONObject().apply {
                                put("peerId", other.peerId)
                                put("name", other.remoteUsername.ifBlank { other.remoteIp.ifBlank { "Chatso User" } })
                                put("pub", other.remotePubB64)
                                put("port", other.remoteListenPort)
                                put("disc", true)
                                if (other.remoteInstanceId.isNotBlank()) put("inst", other.remoteInstanceId)
                            }
                        )
                    }
            }
            val payload = JSONObject().apply {
                put("cmd", "BRIDGE_PEERS")
                put("bridgeId", identity.peerId)
                put("bridgeName", identity.username)
                put("peers", peersArray)
            }.toString().toByteArray(Charsets.UTF_8)
            runCatching { recipient.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload) }
        }
    }

    private suspend fun broadcastAppState(inForeground: Boolean) {
        val payload = JSONObject().apply {
            put("cmd", "APP_STATE")
            put("fg", inForeground)
        }.toString().toByteArray(Charsets.UTF_8)
        for (session in sessions.values) {
            if (session.isSilentDismissed) continue
            runCatching { session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload) }
        }
    }

    suspend fun sendTextMessage(
        peerId: String,
        messageId: String,
        text: String,
        selfDestructSec: Int,
        createdAt: Long,
        replyToMessageId: String? = null,
        replyToAuthorType: String? = null,
        replyToPreview: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = sessions[peerId] ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                put("cmd", "TEXT_MSG")
                put("id", messageId)
                put("text", text)
                put("sd", selfDestructSec)
                put("ts", createdAt)
                if (!replyToMessageId.isNullOrBlank()) {
                    put("replyId", replyToMessageId)
                    if (!replyToAuthorType.isNullOrBlank()) put("replyAuthorType", replyToAuthorType)
                    if (!replyToPreview.isNullOrBlank()) put("replyPreview", replyToPreview)
                }
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        } catch (_: Exception) {
            cleanupSession(session)
            false
        }
    }

    suspend fun sendReaction(
        peerId: String,
        messageId: String,
        emoji: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = sessions[peerId] ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                put("cmd", "REACTION")
                put("targetId", messageId)
                put("emoji", emoji)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancelIncomingTransfer(peerId: String, messageId: String) {
        val session = sessions[peerId] ?: return
        session.cancelledIncomingIds.add(messageId)
        val transfer = session.incomingTransfers.remove(messageId) ?: return
        synchronized(transfer) {
            transfer.cancelled = true
            runCatching { transfer.outputStream.close() }
        }
        runCatching { if (transfer.file.exists()) transfer.file.delete() }
    }

    suspend fun sendFileCancel(peerId: String, messageId: String, bySender: Boolean): Boolean {
        val session = sessions[peerId] ?: return false
        return runCatching {
            val payload = JSONObject().apply {
                put("cmd", "FILE_CANCEL")
                put("id", messageId)
                put("bySender", bySender)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        }.getOrDefault(false)
    }

    suspend fun sendFileRetryRequest(peerId: String, messageId: String): Boolean {
        val session = sessions[peerId] ?: return false
        session.cancelledIncomingIds.remove(messageId)
        return runCatching {
            val payload = JSONObject().apply {
                put("cmd", "FILE_RETRY")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        }.getOrDefault(false)
    }

    suspend fun sendFileUnavailable(peerId: String, messageId: String): Boolean {
        val session = sessions[peerId] ?: return false
        return runCatching {
            val payload = JSONObject().apply {
                put("cmd", "FILE_UNAVAILABLE")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        }.getOrDefault(false)
    }

    suspend fun sendMediaOrFile(
        peerId: String,
        messageId: String,
        messageType: MessageType,
        file: File,
        fileName: String,
        mimeType: String,
        audioDurationMs: Long,
        selfDestructSec: Int,
        createdAt: Long,
        replyToMessageId: String? = null,
        replyToAuthorType: String? = null,
        replyToPreview: String? = null,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val session = sessions[peerId] ?: return@withContext false
        if (isCancelled()) return@withContext false
        try {
            val totalBytes = file.length()
            val totalChunks = ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
            val paddedMsgId = messageId.padEnd(36, ' ').take(36).toByteArray(Charsets.US_ASCII)

            val startJson = JSONObject().apply {
                put("cmd", "FILE_START")
                put("id", messageId)
                put("msgType", messageType.name)
                put("fileName", fileName)
                put("mimeType", mimeType)
                put("totalBytes", totalBytes)
                put("totalChunks", totalChunks)
                put("audioMs", audioDurationMs)
                put("sd", selfDestructSec)
                put("ts", createdAt)
                if (mediaWidth > 0 && mediaHeight > 0) {
                    put("mediaW", mediaWidth)
                    put("mediaH", mediaHeight)
                }
                if (!replyToMessageId.isNullOrBlank()) {
                    put("replyId", replyToMessageId)
                    if (!replyToAuthorType.isNullOrBlank()) put("replyAuthorType", replyToAuthorType)
                    if (!replyToPreview.isNullOrBlank()) put("replyPreview", replyToPreview)
                }
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, startJson)

            var lastPct = 0
            FileInputStream(file).use { fis ->
                val buf = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0
                var sentBytes = 0L
                while (true) {
                    if (isCancelled()) return@withContext false
                    val read = fis.read(buf)
                    if (read <= 0) break
                    val framePlain = ByteBuffer.allocate(40 + read)
                        .put(paddedMsgId)
                        .putInt(chunkIndex++)
                        .put(buf, 0, read)
                        .array()
                    session.sendEncryptedFrame(KIND_ENCRYPTED_CHUNK, framePlain)
                    sentBytes += read
                    val pct = if (totalBytes > 0) {
                        ((sentBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                    } else {
                        99
                    }
                    if (pct != lastPct && !isCancelled()) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }

            if (isCancelled()) return@withContext false
            val endJson = JSONObject().apply {
                put("cmd", "FILE_END")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, endJson)
            onProgress(100)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendTyping(peerId: String, isTyping: Boolean) {
        val session = sessions[peerId] ?: return
        runCatching {
            val payload = JSONObject().apply {
                put("cmd", "TYPING")
                put("typing", isTyping)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
        }
    }

    suspend fun sendReadReceipt(peerId: String, messageId: String): Boolean {
        val session = sessions[peerId]?.takeIf { !it.isClosed } ?: return false
        return runCatching {
            sendReceiptInternal(session, messageId, DeliveryStatus.READ)
            true
        }.getOrDefault(false)
    }

    suspend fun sendDeliveredReceipt(peerId: String, messageId: String): Boolean {
        val session = sessions[peerId]?.takeIf { !it.isClosed } ?: return false
        return runCatching {
            sendReceiptInternal(session, messageId, DeliveryStatus.DELIVERED)
            true
        }.getOrDefault(false)
    }

    suspend fun sendReceiptBatch(
        peerId: String,
        receipts: List<Pair<String, DeliveryStatus>>
    ): Boolean {
        if (receipts.isEmpty()) return true
        val session = sessions[peerId]?.takeIf { !it.isClosed } ?: return false
        return runCatching {
            val arr = JSONArray()
            receipts.forEach { (msgId, status) ->
                arr.put(
                    JSONObject().apply {
                        put("id", msgId)
                        put("status", status.name)
                    }
                )
            }
            val payload = JSONObject().apply {
                put("cmd", "RECEIPT_BATCH")
                put("items", arr)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        }.getOrDefault(false)
    }

    suspend fun sendReceiptQuery(peerId: String, messageIds: List<String>): Boolean {
        if (messageIds.isEmpty()) return true
        val session = sessions[peerId]?.takeIf { !it.isClosed } ?: return false
        return runCatching {
            val arr = JSONArray()
            messageIds.forEach { arr.put(it) }
            val payload = JSONObject().apply {
                put("cmd", "RECEIPT_QUERY")
                put("ids", arr)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        }.getOrDefault(false)
    }

    suspend fun sendSelfDestructConfig(peerId: String, seconds: Int) {
        val session = sessions[peerId] ?: return
        runCatching {
            val payload = JSONObject().apply {
                put("cmd", "SELF_DESTRUCT_CONFIG")
                put("sd", seconds)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
        }
    }

    suspend fun sendVerificationStatus(peerId: String, verified: Boolean) {
        val session = sessions[peerId] ?: return
        runCatching {
            val payload = JSONObject().apply {
                put("cmd", "VERIFY_STATUS")
                put("verified", verified)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
        }
    }

    private suspend fun sendReceiptInternal(
        session: ActivePeerSession,
        messageId: String,
        status: DeliveryStatus
    ) {
        val payload = JSONObject().apply {
            put("cmd", "RECEIPT")
            put("id", messageId)
            put("status", status.name)
        }.toString().toByteArray(Charsets.UTF_8)
        session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
    }

    suspend fun disconnectPeer(peerId: String) = withContext(Dispatchers.IO) {
        pendingBridgedHandshakes.remove(peerId)?.cancel()
        val session = sessions[peerId]
        if (session != null) {
            cleanupSession(session)
        }
    }

    fun disconnectAll() {
        stopServer()
        stopIroh()
        pendingBridgedHandshakes.values.forEach { it.cancel() }
        pendingBridgedHandshakes.clear()
        discoveryManager.clearAllBridgedPeers()
        sessions.values.forEach { it.closeQuietly() }
        sessions.clear()
        _connectedPeers.value = emptySet()
        _foregroundPeers.value = emptySet()
        refreshBridgeTelemetry()
    }
}
