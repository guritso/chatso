package com.example.network

import android.os.Build
import app.azula.iroh.EndpointAddr
import app.azula.iroh.EndpointOptions
import app.azula.iroh.IrohConnection
import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.RelayModeOption
import app.azula.iroh.endpointAddrFromTicket
import com.example.data.IrohRelayMode
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val CHATSO_IROH_ALPN = "chatso/p2p/1"

object IrohRelayUrlUtils {
    private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val BASE32_52_REGEX = Regex("^[a-zA-Z2-7]{52}$")

    fun normalizeSingleRelayUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            val normalizedPath = uri.path?.trimEnd('/').orEmpty()
            val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$portSuffix$normalizedPath"
        } catch (_: Exception) {
            null
        }
    }

    fun parseRelayUrlList(raw: String): List<String>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val normalized = ArrayList<String>(parts.size)
        for (part in parts) {
            val clean = normalizeSingleRelayUrl(part) ?: return null
            if (!normalized.contains(clean)) {
                normalized.add(clean)
            }
        }
        return normalized
    }

    fun looksLikeIrohEndpointId(raw: String): Boolean {
        val clean = raw.trim()
        return HEX_64_REGEX.matches(clean) || BASE32_52_REGEX.matches(clean)
    }

    fun shortEndpointId(endpointId: String): String {
        val clean = endpointId.trim()
        if (clean.length <= 16) return clean
        return "${clean.take(8)}…${clean.takeLast(6)}"
    }
}

class IrohTransportConfig(
    val mode: IrohRelayMode,
    val relayUrls: List<String>,
    val secretKey: ByteArray?
)

interface IrohPeerChannel {
    val remoteEndpointId: String
    val remoteRelayUrl: String
    val isClosed: Boolean
    suspend fun readFrame(maxBytes: Int = 512 * 1024): Pair<Byte, ByteArray>
    suspend fun writeFrame(kind: Byte, payload: ByteArray)
    fun close()
}

interface IrohEndpointSession {
    val endpointId: String
    val activeRelayUrl: String
    val isClosed: Boolean
    suspend fun acceptPeerChannel(): IrohPeerChannel?
    suspend fun connectPeerChannel(
        targetEndpointId: String,
        targetRelayUrl: String = "",
        directAddresses: List<String> = emptyList()
    ): IrohPeerChannel
    fun close()
}

interface IrohTransport {
    fun isSupported(): Boolean = true
    suspend fun startEndpoint(config: IrohTransportConfig): IrohEndpointSession
    fun parseTicket(ticket: String): Pair<String, String>? = null
}

class NativeIrohTransport : IrohTransport {

    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override suspend fun startEndpoint(config: IrohTransportConfig): IrohEndpointSession =
        withContext(Dispatchers.IO) {
            if (config.mode == IrohRelayMode.DISABLED) {
                throw IllegalStateException("Iroh relay is disabled")
            }
            if (!isSupported()) {
                throw UnsupportedOperationException("Iroh relay requires Android 8.0 or newer")
            }
            val relayMode = when (config.mode) {
                IrohRelayMode.DISABLED -> RelayModeOption.Disabled
                IrohRelayMode.PUBLIC -> {
                    if (config.relayUrls.isEmpty()) {
                        RelayModeOption.Default
                    } else {
                        RelayModeOption.Custom(config.relayUrls)
                    }
                }
                IrohRelayMode.PRIVATE -> {
                    val urls = config.relayUrls.filter { it.isNotBlank() }
                    if (urls.isEmpty()) {
                        throw IllegalArgumentException("Private relay URL is required")
                    }
                    RelayModeOption.Custom(urls)
                }
            }
            val options = EndpointOptions(
                alpns = listOf(CHATSO_IROH_ALPN),
                secretKey = config.secretKey,
                relayMode = relayMode,
                warmUpOnline = true
            )
            val endpoint = IrohEndpoint.bindWith(options)
            withTimeoutOrNull(8_000L) {
                runCatching { endpoint.waitOnline() }
            }
            NativeIrohEndpointSession(endpoint)
        }

    override fun parseTicket(ticket: String): Pair<String, String>? {
        if (!isSupported()) return null
        val clean = ticket.trim()
        if (clean.isEmpty()) return null
        return try {
            val addr = endpointAddrFromTicket(clean)
            val id = addr.id.trim()
            if (id.isBlank()) null else (id to addr.relayUrl?.trim().orEmpty())
        } catch (_: Throwable) {
            null
        }
    }
}

private class NativeIrohEndpointSession(
    private val endpoint: IrohEndpoint
) : IrohEndpointSession {

    private val closedFlag = AtomicBoolean(false)

    override val endpointId: String
        get() = runCatching { endpoint.id() }.getOrDefault("")

    override val activeRelayUrl: String
        get() = runCatching { endpoint.homeRelay()?.trim().orEmpty() }.getOrDefault("")

    override val isClosed: Boolean
        get() = closedFlag.get() || runCatching { endpoint.isClosed() }.getOrDefault(true)

    override suspend fun acceptPeerChannel(): IrohPeerChannel? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null
        val conn = endpoint.acceptConn() ?: return@withContext null
        try {
            val stream = withTimeoutOrNull(8_000L) { conn.acceptBi() }
            if (stream == null) {
                runCatching { conn.close() }
                return@withContext null
            }
            val remoteId = runCatching { conn.remoteId()?.trim().orEmpty() }.getOrDefault("")
            val relay = runCatching {
                endpoint.homeRelay()?.trim().orEmpty()
            }.getOrDefault("")
            NativeIrohPeerChannel(
                conn = conn,
                stream = stream,
                remoteEndpointId = remoteId,
                remoteRelayUrl = relay
            )
        } catch (ce: CancellationException) {
            runCatching { conn.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { conn.close() }
            throw IOException("Failed accepting Iroh stream: ${t.message}", t)
        }
    }

    override suspend fun connectPeerChannel(
        targetEndpointId: String,
        targetRelayUrl: String,
        directAddresses: List<String>
    ): IrohPeerChannel = withContext(Dispatchers.IO) {
        if (isClosed) throw IOException("Iroh endpoint is closed")
        val cleanId = targetEndpointId.trim()
        val cleanRelay = targetRelayUrl.trim()
        val conn: IrohConnection = if (cleanRelay.isNotEmpty() || directAddresses.isNotEmpty()) {
            endpoint.connectAddr(
                EndpointAddr(
                    id = cleanId,
                    relayUrl = cleanRelay,
                    directAddresses = directAddresses
                ),
                CHATSO_IROH_ALPN
            )
        } else {
            endpoint.connectById(cleanId, CHATSO_IROH_ALPN)
        }
        try {
            val stream = conn.openBi()
            NativeIrohPeerChannel(
                conn = conn,
                stream = stream,
                remoteEndpointId = cleanId.ifBlank { runCatching { conn.remoteId()?.trim().orEmpty() }.getOrDefault("") },
                remoteRelayUrl = cleanRelay
            )
        } catch (ce: CancellationException) {
            runCatching { conn.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { conn.close() }
            throw IOException("Failed opening Iroh stream: ${t.message}", t)
        }
    }

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            runCatching { endpoint.close() }
        }
    }
}

private class NativeIrohPeerChannel(
    private val conn: IrohConnection,
    private val stream: IrohStream,
    override val remoteEndpointId: String,
    override val remoteRelayUrl: String
) : IrohPeerChannel {

    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val closedFlag = AtomicBoolean(false)

    override val isClosed: Boolean
        get() = closedFlag.get()

    override suspend fun readFrame(maxBytes: Int): Pair<Byte, ByteArray> = readMutex.withLock {
        withContext(Dispatchers.IO) {
            if (closedFlag.get()) throw IOException("Iroh channel closed")
            val header = try {
                stream.readExact(5u)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw IOException("Iroh read header failed: ${t.message}", t)
            }
            if (header.size < 5) throw IOException("Truncated Iroh frame header")
            val kind = header[0]
            val length = ByteBuffer.wrap(header, 1, 4).int
            if (length < 0 || length > maxBytes) {
                throw IOException("Invalid Iroh frame size: $length")
            }
            val payload = if (length == 0) {
                ByteArray(0)
            } else {
                try {
                    stream.readExact(length.toUInt())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    throw IOException("Iroh read payload failed: ${t.message}", t)
                }
            }
            kind to payload
        }
    }

    override suspend fun writeFrame(kind: Byte, payload: ByteArray) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (closedFlag.get()) throw IOException("Iroh channel closed")
            val packet = ByteBuffer.allocate(5 + payload.size)
                .put(kind)
                .putInt(payload.size)
                .put(payload)
                .array()
            try {
                stream.sendBytes(packet)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw IOException("Iroh write failed: ${t.message}", t)
            }
        }
    }

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            runCatching { stream.close() }
            runCatching { conn.shutdown(0uL, ByteArray(0)) }
            runCatching { conn.close() }
        }
    }
}
