package com.example.network

import android.util.Base64
import com.example.crypto.CryptoEngine
import com.example.crypto.SessionCipher
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.MessageType
import com.example.data.ReplySenderTags
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class FileSendResult {
    SENT,
    CANCELLED,
    FAILED
}

interface P2PEventListener {
    suspend fun isKnownPeer(peerId: String): Boolean
    suspend fun onPeerHandshaked(
        peerId: String,
        publicKeyBase64: String,
        remoteIp: String,
        remoteListenPort: Int,
        username: String,
        avatarBase64: String?
    )
    suspend fun onPeerDisconnected(peerId: String)
    suspend fun onPeerForegroundChanged(peerId: String, inForeground: Boolean) {}
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
        replyToPreview: String? = null
    ): File
    suspend fun onIncomingFileProgress(peerId: String, messageId: String, progress: Int)
    suspend fun onIncomingFileComplete(peerId: String, messageId: String, file: File)
    suspend fun onIncomingFileCancelled(peerId: String, messageId: String) {}
    suspend fun onOutgoingFileCancelRequested(peerId: String, messageId: String) {}
    suspend fun onFileRetryRequested(peerId: String, messageId: String) {}
    suspend fun onFileRetryUnavailable(peerId: String, messageId: String) {}
    suspend fun onDeliveryReceipt(peerId: String, messageId: String, status: DeliveryStatus)
    suspend fun isPeerVerifiedByMe(peerId: String): Boolean = false
    suspend fun onPeerVerificationChanged(peerId: String, verifiedMe: Boolean) {}
    suspend fun onIncomingReaction(peerId: String, messageId: String, emoji: String) {}
    suspend fun onTypingChanged(peerId: String, isTyping: Boolean)
    suspend fun onSelfDestructConfigChanged(peerId: String, seconds: Int)
}

class P2PNodeManager(
    private val scope: CoroutineScope,
    private val discoveryManager: DiscoveryManager,
    private val listener: P2PEventListener
) {
    companion object {
        private const val KIND_HANDSHAKE_HELLO: Byte = 0x03
        private const val KIND_HANDSHAKE_WELCOME: Byte = 0x04
        private const val KIND_HANDSHAKE_REJECT: Byte = 0x05
        private const val KIND_ENCRYPTED_CONTROL: Byte = 0x10
        private const val KIND_ENCRYPTED_CHUNK: Byte = 0x11
        private const val CHUNK_SIZE = 192 * 1024 // 192 KB chunks (~192KB encrypted, <=256KB wire limit)
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val SOCKET_STREAM_BUFFER_BYTES = 256 * 1024
        private const val FILE_IO_BUFFER_BYTES = 256 * 1024
        private const val TCP_WINDOW_BYTES = 512 * 1024
    }

    private class ActivePeerSession(
        val peerId: String,
        val socket: Socket,
        val output: DataOutputStream,
        val cipher: SessionCipher,
        val writeMutex: Mutex = Mutex()
    ) {
        suspend fun sendEncryptedFrame(kind: Byte, plaintext: ByteArray, flush: Boolean = true) =
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    val encrypted = cipher.encrypt(kind, plaintext)
                    output.writeByte(kind.toInt())
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                    if (flush) output.flush()
                }
            }

        suspend fun sendEncryptedChunkFrame(
            header40: ByteArray,
            chunkData: ByteArray,
            chunkLen: Int
        ) {
            writeMutex.withLock {
                val encrypted = cipher.encryptChunk(KIND_ENCRYPTED_CHUNK, header40, chunkData, chunkLen)
                output.writeByte(KIND_ENCRYPTED_CHUNK.toInt())
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
        }

        fun closeQuietly() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class IncomingTransfer(
        val file: File,
        val outputStream: OutputStream,
        val totalBytes: Long,
        var receivedBytes: Long = 0L,
        var lastReportedProgress: Int = 0
    )

    @Volatile
    private var currentIdentity: LocalIdentity = LocalIdentity()

    @Volatile
    private var localAppInForeground: Boolean = false

    @Volatile
    private var localAvatarBase64Provider: suspend () -> String? = { null }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val sessions = ConcurrentHashMap<String, ActivePeerSession>()
    private val cancelledOutgoingIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelledIncomingIds = ConcurrentHashMap.newKeySet<String>()

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    private val _foregroundPeers = MutableStateFlow<Set<String>>(emptySet())
    val foregroundPeers: StateFlow<Set<String>> = _foregroundPeers.asStateFlow()

    private val _boundPort = MutableStateFlow(LocalIdentity.DEFAULT_TCP_PORT)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

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

    fun ensureServerRunning(identity: LocalIdentity) {
        currentIdentity = identity
        discoveryManager.applyIdentityPolicy(identity)
        if (!identity.isInitialized) {
            stopServer()
            return
        }
        if (!_serverRunning.value) {
            restartServer(identity.listenPort)
        }
    }

    fun updateIdentityAndEnsureServer(identity: LocalIdentity) {
        val portChanged = currentIdentity.listenPort != identity.listenPort
        val wasInit = currentIdentity.isInitialized
        currentIdentity = identity
        discoveryManager.applyIdentityPolicy(identity)

        if (!identity.isInitialized) {
            stopServer()
            return
        }
        if (!wasInit || portChanged || !_serverRunning.value) {
            restartServer(identity.listenPort)
        } else {
            scope.launch(Dispatchers.IO) {
                broadcastProfileSync()
            }
        }
    }

    private fun restartServer(preferredPort: Int) {
        stopServer()
        acceptJob = scope.launch(Dispatchers.IO) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    runCatching { receiveBufferSize = TCP_WINDOW_BYTES }
                    bind(InetSocketAddress("0.0.0.0", preferredPort))
                }
            } catch (_: Exception) {
                try {
                    ServerSocket().apply {
                        reuseAddress = true
                        runCatching { receiveBufferSize = TCP_WINDOW_BYTES }
                        bind(InetSocketAddress("0.0.0.0", 0))
                    }
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

    suspend fun connectToPeer(
        host: String,
        port: Int,
        expectedPubKeyBase64: String = "",
        inviteToken: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            return@withContext Result.failure(IllegalStateException("Local identity is not initialized yet"))
        }
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            runCatching {
                socket.sendBufferSize = TCP_WINDOW_BYTES
                socket.receiveBufferSize = TCP_WINDOW_BYTES
            }
            socket.connect(InetSocketAddress(host.trim(), port), 2000)
            socket.soTimeout = 5_000

            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_STREAM_BUFFER_BYTES))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_STREAM_BUFFER_BYTES))

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

            val helloBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", ephPubB64)
                put("nonce", clientNonceB64)
                put("sig", signatureB64)
                put("port", _boundPort.value)
                put("token", inviteToken)
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

            if (expectedPubKeyBase64.isNotBlank() && expectedPubKeyBase64 != serverPubB64) {
                socket.close()
                return@withContext Result.failure(
                    SecurityException("Public key mismatch against QR invite. Possible interception blocked.")
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
            val rawPeerId = CryptoEngine.computePeerIdFromBase64(serverPubB64)
            val isSelfLoopback = rawPeerId == identity.peerId
            val sessionPeerId = if (isSelfLoopback) "loopback_${rawPeerId.take(12)}" else rawPeerId

            val session = ActivePeerSession(sessionPeerId, socket, out, cipher)
            registerActiveSession(sessionPeerId, session)

            sendProfileSync(session, identity)

            listener.onPeerHandshaked(
                peerId = sessionPeerId,
                publicKeyBase64 = serverPubB64,
                remoteIp = host.trim(),
                remoteListenPort = serverListenPort,
                username = if (isSelfLoopback) "${identity.username} (Localhost)" else host.trim(),
                avatarBase64 = null
            )

            scope.launch(Dispatchers.IO) {
                runSessionReadLoop(
                    session = session,
                    inp = inp,
                    remotePubB64 = serverPubB64,
                    remoteIp = host.trim(),
                    remoteListenPort = serverListenPort,
                    isSelfLoopbackResponder = false
                )
            }
            Result.success(sessionPeerId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun handleInboundSocket(socket: Socket) {
        val identity = currentIdentity
        if (!identity.isInitialized) {
            socket.close()
            return
        }
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            runCatching {
                socket.sendBufferSize = TCP_WINDOW_BYTES
                socket.receiveBufferSize = TCP_WINDOW_BYTES
            }
            socket.soTimeout = 8_000
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_STREAM_BUFFER_BYTES))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_STREAM_BUFFER_BYTES))

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
                    out.writeByte(DiscoveryManager.KIND_DISCOVERY_REPLY.toInt())
                    out.writeInt(reply.size)
                    out.write(reply)
                    out.flush()
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

            val remotePeerId = CryptoEngine.computePeerIdFromBase64(clientPubB64)
            val isSelfLoopback = remotePeerId == identity.peerId

            if (!isSelfLoopback && identity.blockUnknownPeers) {
                val isKnown = listener.isKnownPeer(remotePeerId)
                val validToken = inviteToken.isNotBlank() && inviteToken == identity.qrInviteToken
                if (!isKnown && !validToken) {
                    val rej = JSONObject().apply {
                        put("reason", "This device only accepts connections from saved contacts or QR invites.")
                    }.toString().toByteArray(Charsets.UTF_8)
                    out.writeByte(KIND_HANDSHAKE_REJECT.toInt())
                    out.writeInt(rej.size)
                    out.write(rej)
                    out.flush()
                    socket.close()
                    return
                }
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

            val welcomeBytes = JSONObject().apply {
                put("pub", identity.publicKeyBase64)
                put("eph", serverEphB64)
                put("nonce", serverNonceB64)
                put("sig", serverSigB64)
                put("port", _boundPort.value)
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
            val remoteIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"

            if (isSelfLoopback) {
                val responderSession = ActivePeerSession("loopback_srv", socket, out, cipher)
                runSessionReadLoop(
                    session = responderSession,
                    inp = inp,
                    remotePubB64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteListenPort = clientListenPort,
                    isSelfLoopbackResponder = true
                )
            } else {
                val session = ActivePeerSession(remotePeerId, socket, out, cipher)
                registerActiveSession(remotePeerId, session)
                sendProfileSync(session, identity)
                listener.onPeerHandshaked(
                    peerId = remotePeerId,
                    publicKeyBase64 = clientPubB64,
                    remoteIp = remoteIp,
                    remoteListenPort = clientListenPort,
                    username = remoteIp,
                    avatarBase64 = null
                )
                runSessionReadLoop(
                    session = session,
                    inp = inp,
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

    private suspend fun runSessionReadLoop(
        session: ActivePeerSession,
        inp: DataInputStream,
        remotePubB64: String,
        remoteIp: String,
        remoteListenPort: Int,
        isSelfLoopbackResponder: Boolean
    ) {
        val incomingTransfers = HashMap<String, IncomingTransfer>()
        try {
            while (scope.isActive && !session.socket.isClosed) {
                val kind = inp.readByte()
                val length = inp.readInt()
                if (length <= 0 || length > MAX_FRAME_BYTES) break
                val encryptedPacket = ByteArray(length)
                inp.readFully(encryptedPacket)

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
                            incomingTransfers = incomingTransfers,
                            isSelfLoopbackResponder = isSelfLoopbackResponder
                        )
                    }
                    KIND_ENCRYPTED_CHUNK -> {
                        handleChunkPacket(
                            session = session,
                            plaintext = plaintext,
                            incomingTransfers = incomingTransfers,
                            isSelfLoopbackResponder = isSelfLoopbackResponder
                        )
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            incomingTransfers.values.forEach {
                try {
                    it.outputStream.close()
                } catch (_: Exception) {
                }
            }
            session.closeQuietly()
            if (!isSelfLoopbackResponder) {
                val removed = sessions.remove(session.peerId, session)
                if (removed) {
                    _connectedPeers.update { it - session.peerId }
                    _foregroundPeers.update { it - session.peerId }
                    listener.onPeerDisconnected(session.peerId)
                }
            }
        }
    }

    private suspend fun handleControlPacket(
        session: ActivePeerSession,
        json: JSONObject,
        remotePubB64: String,
        remoteIp: String,
        remoteListenPort: Int,
        incomingTransfers: HashMap<String, IncomingTransfer>,
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
                _foregroundPeers.update { if (isFg) it + session.peerId else it - session.peerId }
                listener.onPeerForegroundChanged(session.peerId, isFg)
                listener.onPeerHandshaked(
                    peerId = session.peerId,
                    publicKeyBase64 = remotePubB64,
                    remoteIp = remoteIp,
                    remoteListenPort = listenPort,
                    username = name,
                    avatarBase64 = avatarB64
                )
                if (json.has("verifiedPeer")) {
                    listener.onPeerVerificationChanged(session.peerId, json.optBoolean("verifiedPeer", false))
                }
            }
            "VERIFY_STATUS" -> {
                listener.onPeerVerificationChanged(session.peerId, json.optBoolean("verified", false))
            }
            "APP_STATE" -> {
                val isFg = json.optBoolean("fg", false)
                _foregroundPeers.update { if (isFg) it + session.peerId else it - session.peerId }
                listener.onPeerForegroundChanged(session.peerId, isFg)
            }
            "TEXT_MSG" -> {
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
                sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
            }
            "REACTION" -> {
                val targetId = json.optString("targetId")
                val emoji = json.optString("emoji", "")
                if (targetId.isNotBlank()) {
                    listener.onIncomingReaction(session.peerId, targetId, emoji)
                }
            }
            "TYPING" -> {
                listener.onTypingChanged(session.peerId, json.optBoolean("typing", false))
            }
            "RECEIPT" -> {
                val msgId = json.getString("id")
                val statusStr = json.optString("status", DeliveryStatus.DELIVERED.name)
                val status = DeliveryStatus.entries.firstOrNull { it.name == statusStr }
                    ?: DeliveryStatus.DELIVERED
                listener.onDeliveryReceipt(session.peerId, msgId, status)
            }
            "SELF_DESTRUCT_CONFIG" -> {
                listener.onSelfDestructConfigChanged(session.peerId, json.optInt("sd", 0))
            }
            "FILE_START" -> {
                val msgId = json.getString("id")
                cancelledIncomingIds.remove(msgId)
                incomingTransfers.remove(msgId)?.let { stale ->
                    runCatching { stale.outputStream.close() }
                }
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
                    replyToPreview = replyPreview
                )
                incomingTransfers[msgId] = IncomingTransfer(
                    file = outFile,
                    outputStream = BufferedOutputStream(FileOutputStream(outFile), FILE_IO_BUFFER_BYTES),
                    totalBytes = totalBytes.coerceAtLeast(1L)
                )
            }
            "FILE_END" -> {
                val msgId = json.getString("id")
                val transfer = incomingTransfers.remove(msgId)
                if (transfer != null) {
                    if (cancelledIncomingIds.remove(msgId)) {
                        runCatching { transfer.outputStream.close() }
                        runCatching { transfer.file.delete() }
                    } else {
                        transfer.outputStream.flush()
                        transfer.outputStream.close()
                        listener.onIncomingFileComplete(session.peerId, msgId, transfer.file)
                        sendReceiptInternal(session, msgId, DeliveryStatus.DELIVERED)
                    }
                }
            }
            "FILE_CANCEL" -> {
                val msgId = json.optString("id")
                if (msgId.isNotBlank()) {
                    cancelledIncomingIds.add(msgId)
                    val incoming = incomingTransfers.remove(msgId)
                    if (incoming != null) {
                        runCatching { incoming.outputStream.close() }
                        runCatching { incoming.file.delete() }
                        listener.onIncomingFileCancelled(session.peerId, msgId)
                    }
                    cancelledOutgoingIds.add(msgId)
                    listener.onOutgoingFileCancelRequested(session.peerId, msgId)
                }
            }
            "FILE_RETRY_REQ" -> {
                val msgId = json.optString("id")
                if (msgId.isNotBlank()) {
                    cancelledOutgoingIds.remove(msgId)
                    listener.onFileRetryRequested(session.peerId, msgId)
                }
            }
            "FILE_UNAVAILABLE" -> {
                val msgId = json.optString("id")
                if (msgId.isNotBlank()) {
                    listener.onFileRetryUnavailable(session.peerId, msgId)
                }
            }
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
        incomingTransfers: HashMap<String, IncomingTransfer>,
        isSelfLoopbackResponder: Boolean
    ) {
        if (isSelfLoopbackResponder) return
        if (plaintext.size <= 40) return
        val msgId = String(plaintext, 0, 36, Charsets.US_ASCII).trim()
        if (cancelledIncomingIds.contains(msgId)) return
        val transfer = incomingTransfers[msgId] ?: return
        val dataLen = plaintext.size - 40
        transfer.outputStream.write(plaintext, 40, dataLen)
        transfer.receivedBytes += dataLen
        val progress = ((transfer.receivedBytes * 100L) / transfer.totalBytes)
            .toInt()
            .coerceIn(1, 99)
        if (progress != transfer.lastReportedProgress) {
            transfer.lastReportedProgress = progress
            listener.onIncomingFileProgress(session.peerId, msgId, progress)
        }
    }

    private fun registerActiveSession(peerId: String, session: ActivePeerSession) {
        val old = sessions.put(peerId, session)
        old?.closeQuietly()
        _connectedPeers.update { it + peerId }
    }

    private suspend fun sendProfileSync(session: ActivePeerSession, identity: LocalIdentity) {
        val avatarB64 = localAvatarBase64Provider()
        val verifiedPeer = listener.isPeerVerifiedByMe(session.peerId)
        val syncJson = JSONObject().apply {
            put("cmd", "PROFILE_SYNC")
            put("name", identity.username)
            put("port", _boundPort.value)
            put("fg", localAppInForeground)
            put("verifiedPeer", verifiedPeer)
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
            runCatching { sendProfileSync(session, identity) }
        }
    }

    private suspend fun broadcastAppState(inForeground: Boolean) {
        val payload = JSONObject().apply {
            put("cmd", "APP_STATE")
            put("fg", inForeground)
        }.toString().toByteArray(Charsets.UTF_8)
        for (session in sessions.values) {
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
            session.closeQuietly()
            if (sessions.remove(peerId, session)) {
                _connectedPeers.update { it - peerId }
                _foregroundPeers.update { it - peerId }
            }
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

    fun clearTransferCancellation(messageId: String) {
        cancelledOutgoingIds.remove(messageId)
        cancelledIncomingIds.remove(messageId)
    }

    suspend fun cancelTransfer(peerId: String, messageId: String, isOutgoing: Boolean) {
        if (isOutgoing) {
            cancelledOutgoingIds.add(messageId)
        } else {
            cancelledIncomingIds.add(messageId)
        }
        val session = sessions[peerId] ?: return
        runCatching {
            val payload = JSONObject().apply {
                put("cmd", "FILE_CANCEL")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
        }
    }

    suspend fun sendFileRetryRequest(peerId: String, messageId: String): Boolean = withContext(Dispatchers.IO) {
        cancelledIncomingIds.remove(messageId)
        val session = sessions[peerId] ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                put("cmd", "FILE_RETRY_REQ")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendFileUnavailable(peerId: String, messageId: String) {
        val session = sessions[peerId] ?: return
        runCatching {
            val payload = JSONObject().apply {
                put("cmd", "FILE_UNAVAILABLE")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, payload)
        }
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
        onProgress: suspend (Int) -> Unit
    ): FileSendResult = withContext(Dispatchers.IO) {
        cancelledOutgoingIds.remove(messageId)
        val session = sessions[peerId] ?: return@withContext FileSendResult.FAILED
        try {
            val totalBytes = file.length()
            val totalChunks = ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
            val paddedMsgId = messageId.padEnd(36, ' ').take(36).toByteArray(Charsets.US_ASCII)
            val header40 = ByteArray(40)
            System.arraycopy(paddedMsgId, 0, header40, 0, 36)

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
                if (!replyToMessageId.isNullOrBlank()) {
                    put("replyId", replyToMessageId)
                    if (!replyToAuthorType.isNullOrBlank()) put("replyAuthorType", replyToAuthorType)
                    if (!replyToPreview.isNullOrBlank()) put("replyPreview", replyToPreview)
                }
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, startJson)

            var lastPct = 0
            var cancelled = false
            FileInputStream(file).use { fis ->
                val buf = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0
                var sentBytes = 0L
                while (true) {
                    if (cancelledOutgoingIds.contains(messageId) || !currentCoroutineContext().isActive) {
                        cancelled = true
                        break
                    }
                    val read = fis.read(buf)
                    if (read <= 0) break
                    val idx = chunkIndex++
                    header40[36] = (idx ushr 24).toByte()
                    header40[37] = (idx ushr 16).toByte()
                    header40[38] = (idx ushr 8).toByte()
                    header40[39] = idx.toByte()

                    session.sendEncryptedChunkFrame(header40, buf, read)
                    sentBytes += read
                    val pct = if (totalBytes > 0) {
                        ((sentBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                    } else {
                        99
                    }
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }

            if (cancelled || cancelledOutgoingIds.remove(messageId)) {
                val cancelJson = JSONObject().apply {
                    put("cmd", "FILE_CANCEL")
                    put("id", messageId)
                }.toString().toByteArray(Charsets.UTF_8)
                runCatching { session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, cancelJson) }
                return@withContext FileSendResult.CANCELLED
            }

            val endJson = JSONObject().apply {
                put("cmd", "FILE_END")
                put("id", messageId)
            }.toString().toByteArray(Charsets.UTF_8)
            session.sendEncryptedFrame(KIND_ENCRYPTED_CONTROL, endJson)
            onProgress(100)
            FileSendResult.SENT
        } catch (_: Exception) {
            if (cancelledOutgoingIds.remove(messageId)) {
                FileSendResult.CANCELLED
            } else {
                FileSendResult.FAILED
            }
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

    suspend fun sendReadReceipt(peerId: String, messageId: String) {
        val session = sessions[peerId] ?: return
        runCatching { sendReceiptInternal(session, messageId, DeliveryStatus.READ) }
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

    fun disconnectAll() {
        stopServer()
        sessions.values.forEach { it.closeQuietly() }
        sessions.clear()
        _connectedPeers.value = emptySet()
        _foregroundPeers.value = emptySet()
    }
}
