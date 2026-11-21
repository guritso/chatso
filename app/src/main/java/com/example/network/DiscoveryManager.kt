package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.R
import com.example.crypto.CryptoEngine
import com.example.data.DiscoveredPeer
import com.example.data.EndpointKind
import com.example.data.LocalIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles passive multi-network peer discovery (Localhost, LAN/Wi-Fi, OpenVPN/Tailscale VPN,
 * and discoverable Bridged peers) while strictly honoring Invisible Mode (`isDiscoverable == false`).
 *
 * Discovery NEVER performs handshakes and NEVER creates chat entries in persistence.
 */
class DiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    data class BridgedPeerInfo(
        val peerId: String,
        val username: String,
        val publicKeyBase64: String,
        val listenPort: Int,
        val isDiscoverable: Boolean = true
    )

    companion object {
        private const val NSD_SERVICE_TYPE = "_chatso._tcp."
        private const val UDP_MAGIC = "CHATSO_DISC_V1"
        const val KIND_DISCOVERY_PROBE: Byte = 0x01
        const val KIND_DISCOVERY_REPLY: Byte = 0x02
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val _discoveredPeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile
    private var latestIdentity: LocalIdentity = LocalIdentity()

    @Volatile
    private var bridgedPeersProvider: () -> List<BridgedPeerInfo> = { emptyList() }

    fun setBridgedPeersProvider(provider: () -> List<BridgedPeerInfo>) {
        bridgedPeersProvider = provider
    }

    fun openTcpSocket(host: String, port: Int, connectTimeoutMs: Int = 1500): Socket {
        return NetworkUtils.openTcpSocket(
            host = host,
            port = port,
            connectTimeoutMs = connectTimeoutMs
        )
    }

    private var udpResponderJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun applyIdentityPolicy(identity: LocalIdentity) {
        latestIdentity = identity
        if (!identity.isInitialized || !identity.isDiscoverable) {
            stopAdvertising()
        } else {
            startAdvertising(identity)
        }
    }

    private fun startAdvertising(identity: LocalIdentity) {
        if (udpResponderJob?.isActive != true) {
            udpResponderJob = scope.launch(Dispatchers.IO) {
                runUdpResponderLoop()
            }
        }
        registerNsdService(identity)
    }

    private fun stopAdvertising() {
        udpResponderJob?.cancel()
        udpResponderJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        unregisterNsdService()
    }

    private suspend fun runUdpResponderLoop() {
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("chatso_disc_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LocalIdentity.DISCOVERY_UDP_PORT))
            }
            udpSocket = socket
            val buf = ByteArray(2048)
            while (scope.isActive && latestIdentity.isDiscoverable) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val current = latestIdentity
                if (!current.isDiscoverable || !current.isInitialized) continue

                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = JSONObject(msg)
                if (json.optString("magic") != UDP_MAGIC) continue

                val type = json.optString("type")
                if (type == "QUERY") {
                    val senderPeerId = json.optString("peerId")
                    if (senderPeerId == current.peerId) continue
                    val beaconStr = buildBeaconJson(current, "ANNOUNCE") ?: continue
                    val replyBytes = beaconStr.toByteArray(Charsets.UTF_8)
                    val replyPacket = DatagramPacket(
                        replyBytes,
                        replyBytes.size,
                        packet.address,
                        packet.port
                    )
                    socket.send(replyPacket)
                } else if (type == "ANNOUNCE") {
                    val senderIp = NetworkUtils.normalizeHost(packet.address.hostAddress ?: "")
                    val badge = if (packet.address.isLoopbackAddress ||
                        NetworkUtils.isLoopbackHost(senderIp) ||
                        NetworkUtils.isEmulatorHost(senderIp)
                    ) {
                        EndpointKind.LOCALHOST.badge
                    } else {
                        EndpointKind.LAN.badge
                    }
                    handleIncomingBeacon(json, senderIp, badge)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                udpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {
            }
        }
    }

    fun scanNetworkNow(knownTargets: List<Pair<String, Int>> = emptyList()) {
        val identity = latestIdentity
        if (!identity.isInitialized) return
        if (!_isScanning.compareAndSet(false, true)) return
        _discoveredPeers.value = emptyMap()

        scope.launch(Dispatchers.IO) {
            try {
                val scanTargets = NetworkUtils.getActiveNetworkScanTargets(context)
                startNsdBrowse()
                withTimeoutOrNull(2500L) {
                    coroutineScope {
                        // 1. Localhost multi-port + emulator host
                        val loopbackPorts = (
                            listOf(identity.listenPort, LocalIdentity.DEFAULT_TCP_PORT) +
                                (LocalIdentity.DEFAULT_TCP_PORT..(LocalIdentity.DEFAULT_TCP_PORT + 10))
                            ).distinct()
                        val emulatorPorts = listOf(identity.listenPort, LocalIdentity.DEFAULT_TCP_PORT).distinct()

                        launch {
                            loopbackPorts.forEach { port ->
                                launch {
                                    probeSingleHost(
                                        ip = "127.0.0.1",
                                        port = port,
                                        badge = EndpointKind.LOCALHOST.badge,
                                        connectTimeoutMs = 200,
                                        readTimeoutMs = 350
                                    )
                                }
                            }
                            emulatorPorts.forEach { port ->
                                launch {
                                    probeSingleHost(
                                        ip = "10.0.2.2",
                                        port = port,
                                        badge = EndpointKind.LOCALHOST.badge,
                                        connectTimeoutMs = 180,
                                        readTimeoutMs = 300
                                    )
                                }
                            }
                        }

                        // 2. Known endpoints (fast direct discovery probe — never initiates a chat handshake)
                        if (knownTargets.isNotEmpty()) {
                            launch {
                                knownTargets.distinct().forEach { (ip, port) ->
                                    if (ip.isNotBlank() && port > 0) {
                                        launch {
                                            val kindBadge = NetworkUtils.classifyIpKind(ip, scanTargets)?.badge ?: "Saved"
                                            probeSingleHost(
                                                ip = ip,
                                                port = port,
                                                badge = kindBadge,
                                                connectTimeoutMs = 600,
                                                readTimeoutMs = 800
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. UDP broadcast discovery query
                        launch { sendUdpQuery(identity, scanTargets) }

                        // 4. Parallel /24 TCP discovery probe across LAN & VPN (OpenVPN / Tailscale)
                        launch { probeActiveSubnets(identity, scanTargets) }

                        launch { delay(1800L) }
                    }
                }
            } finally {
                stopNsdBrowse()
                _isScanning.value = false
            }
        }
    }

    private suspend fun sendUdpQuery(identity: LocalIdentity, scanTargets: List<NetworkScanTarget>) = coroutineScope {
        val queryJson = JSONObject().apply {
            put("magic", UDP_MAGIC)
            put("type", "QUERY")
            put("peerId", if (identity.isDiscoverable) identity.peerId else "anon")
        }.toString().toByteArray(Charsets.UTF_8)

        val nonLoopbackTargets = scanTargets.filter {
            it.kind == EndpointKind.LAN || it.kind == EndpointKind.VPN
        }

        for (target in nonLoopbackTargets) {
            launch(Dispatchers.IO) {
                try {
                    val sock = runCatching {
                        DatagramSocket(InetSocketAddress(target.ipAddress, 0))
                    }.getOrElse { DatagramSocket() }
                    sock.use { s ->
                        s.broadcast = true
                        s.soTimeout = 450
                        val broadcastTargets = buildList {
                            target.broadcastAddress?.takeIf { it.isNotBlank() }?.let { add(it) }
                            NetworkUtils.subnetPrefix24(target.ipAddress)?.let { add("$it.255") }
                            if (target.kind == EndpointKind.LAN) add("255.255.255.255")
                        }.distinct()
                        for (addrStr in broadcastTargets) {
                            try {
                                val pkt = DatagramPacket(
                                    queryJson,
                                    queryJson.size,
                                    InetAddress.getByName(addrStr),
                                    LocalIdentity.DISCOVERY_UDP_PORT
                                )
                                s.send(pkt)
                            } catch (_: Exception) {
                            }
                        }
                        collectUdpReplies(s, target.kind.badge)
                    }
                } catch (_: Exception) {
                }
            }
        }

        launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 450
                    val targets = mutableSetOf("255.255.255.255", "127.0.0.1")
                    nonLoopbackTargets
                        .mapNotNull { it.broadcastAddress ?: NetworkUtils.subnetPrefix24(it.ipAddress)?.let { p -> "$p.255" } }
                        .forEach { targets.add(it) }
                    for (addrStr in targets) {
                        try {
                            val pkt = DatagramPacket(
                                queryJson,
                                queryJson.size,
                                InetAddress.getByName(addrStr),
                                LocalIdentity.DISCOVERY_UDP_PORT
                            )
                            sock.send(pkt)
                        } catch (_: Exception) {
                        }
                    }
                    collectUdpReplies(sock, EndpointKind.LAN.badge)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun collectUdpReplies(sock: DatagramSocket, fallbackBadge: String) {
        val endAt = System.currentTimeMillis() + 550L
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < endAt) {
            try {
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                val json = JSONObject(String(resp.data, 0, resp.length, Charsets.UTF_8))
                if (json.optString("magic") == UDP_MAGIC && json.optString("type") == "ANNOUNCE") {
                    val senderIp = NetworkUtils.normalizeHost(resp.address.hostAddress ?: "")
                    val effectiveBadge = if (resp.address.isLoopbackAddress ||
                        NetworkUtils.isLoopbackHost(senderIp) ||
                        NetworkUtils.isEmulatorHost(senderIp)
                    ) {
                        EndpointKind.LOCALHOST.badge
                    } else {
                        fallbackBadge
                    }
                    handleIncomingBeacon(json, senderIp, effectiveBadge)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private suspend fun probeActiveSubnets(
        identity: LocalIdentity,
        scanTargets: List<NetworkScanTarget>
    ) = withContext(Dispatchers.IO) {
        val networkTargets = scanTargets.filter {
            it.kind == EndpointKind.LAN || it.kind == EndpointKind.VPN
        }
        if (networkTargets.isEmpty()) return@withContext

        val portsToProbe = listOf(identity.listenPort, LocalIdentity.DEFAULT_TCP_PORT).distinct()
        val globalSemaphore = Semaphore(32)
        val perNetworkWorkers = (32 / networkTargets.size).coerceIn(12, 32)

        coroutineScope {
            for (ep in networkTargets) {
                val prefix = NetworkUtils.subnetPrefix24(ep.ipAddress) ?: continue
                val candidates = ConcurrentLinkedQueue<Pair<String, Int>>()
                for (port in portsToProbe) {
                    if (ep.prefixLength in 1..23) {
                        val octets = ep.ipAddress.split(".")
                        if (octets.size == 4 && octets[2] != "0") {
                            val hubIp = "${octets[0]}.${octets[1]}.0.1"
                            if (hubIp != ep.ipAddress) {
                                candidates.add(hubIp to port)
                            }
                        }
                    }
                    for (hostOctet in 1..254) {
                        val candidateIp = "$prefix.$hostOctet"
                        if (candidateIp != ep.ipAddress) {
                            candidates.add(candidateIp to port)
                        }
                    }
                }

                val connectTimeout = if (ep.kind == EndpointKind.VPN) 260 else 190

                launch {
                    coroutineScope {
                        repeat(perNetworkWorkers) {
                            launch {
                                while (isActive) {
                                    val target = candidates.poll() ?: break
                                    globalSemaphore.withPermit {
                                        probeSingleHost(
                                            ip = target.first,
                                            port = target.second,
                                            badge = ep.kind.badge,
                                            connectTimeoutMs = connectTimeout,
                                            readTimeoutMs = 350
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun probeSingleHost(
        ip: String,
        port: Int,
        badge: String,
        connectTimeoutMs: Int = 120,
        readTimeoutMs: Int = 200
    ): Boolean {
        val cleanIp = NetworkUtils.normalizeHost(ip)
        return try {
            NetworkUtils.openTcpSocket(
                host = cleanIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs
            ).use { socket ->
                socket.soTimeout = readTimeoutMs
                val out = DataOutputStream(socket.getOutputStream())
                val inp = DataInputStream(socket.getInputStream())
                out.writeByte(KIND_DISCOVERY_PROBE.toInt())
                out.writeInt(0)
                out.flush()

                val replyKind = inp.readByte()
                if (replyKind != KIND_DISCOVERY_REPLY) return false
                val len = inp.readInt()
                if (len <= 0 || len > 65_536) return false
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                handleIncomingBeacon(json, cleanIp, badge)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sourcePriority(source: String): Int = when (source) {
        EndpointKind.LOCALHOST.badge -> 0
        EndpointKind.LAN.badge -> 1
        "mDNS" -> 2
        "UDP LAN", "LAN" -> 3
        EndpointKind.VPN.badge -> 4
        "Saved" -> 5
        else -> 6
    }

    private fun handleIncomingBeacon(json: JSONObject, sourceIp: String, sourceLabel: String) {
        // Strictly reject peers that identify as Invisible Mode
        if (json.has("disc") && !json.optBoolean("disc", true)) return
        val pubB64 = json.optString("pub")
        if (pubB64.isBlank() || sourceIp.isBlank()) return
        val peerId = CryptoEngine.computePeerIdFromBase64(pubB64)
        if (peerId == latestIdentity.peerId) return
        val name = json.optString("name", "Chatso User")
        val port = json.optInt("port", LocalIdentity.DEFAULT_TCP_PORT)
        val peer = DiscoveredPeer(
            peerId = peerId,
            username = name,
            ipAddress = sourceIp,
            port = port,
            publicKeyBase64 = pubB64,
            fingerprint = CryptoEngine.formatFingerprintFromBase64(pubB64),
            source = sourceLabel
        )
        _discoveredPeers.update { current ->
            val existing = current[peerId]
            if (existing == null ||
                !existing.viaBridgePeerId.isNullOrBlank() ||
                sourcePriority(sourceLabel) <= sourcePriority(existing.source)
            ) {
                current + (peerId to peer)
            } else {
                current
            }
        }

        val bridgedArr = json.optJSONArray("bridgedPeers")
        if (bridgedArr != null) {
            val entries = buildList {
                for (i in 0 until bridgedArr.length()) {
                    val obj = bridgedArr.optJSONObject(i) ?: continue
                    if (obj.has("disc") && !obj.optBoolean("disc", true)) continue
                    val bPub = obj.optString("pub")
                    if (bPub.isBlank()) continue
                    val bPeerId = CryptoEngine.computePeerIdFromBase64(bPub)
                    if (bPeerId == latestIdentity.peerId || bPeerId == peerId) continue
                    add(
                        BridgedPeerInfo(
                            peerId = bPeerId,
                            username = obj.optString("name", "Chatso User"),
                            publicKeyBase64 = bPub,
                            listenPort = obj.optInt("port", LocalIdentity.DEFAULT_TCP_PORT),
                            isDiscoverable = obj.optBoolean("disc", true)
                        )
                    )
                }
            }
            updateBridgedPeers(
                bridgePeerId = peerId,
                bridgeName = name,
                bridgeHost = sourceIp,
                bridgePort = port,
                entries = entries
            )
        }
    }

    fun updateBridgedPeers(
        bridgePeerId: String,
        bridgeName: String,
        bridgeHost: String,
        bridgePort: Int,
        entries: List<BridgedPeerInfo>
    ) {
        val bridgeBadge = context.getString(R.string.bridge_badge)
        val cleanBridgeName = bridgeName.ifBlank { bridgeHost.ifBlank { bridgeBadge } }
        val sourceText = "$bridgeBadge ($cleanBridgeName)"
        val validEntries = entries.filter {
            it.isDiscoverable &&
                it.peerId != latestIdentity.peerId &&
                it.peerId != bridgePeerId &&
                it.publicKeyBase64.isNotBlank()
        }
        val activeIds = validEntries.map { it.peerId }.toSet()
        _discoveredPeers.update { current ->
            val mutable = current.toMutableMap()
            val iterator = mutable.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.viaBridgePeerId == bridgePeerId && !activeIds.contains(entry.key)) {
                    iterator.remove()
                }
            }
            for (item in validEntries) {
                val existing = mutable[item.peerId]
                if (existing == null || existing.viaBridgePeerId != null) {
                    mutable[item.peerId] = DiscoveredPeer(
                        peerId = item.peerId,
                        username = item.username.ifBlank { "Chatso User" },
                        ipAddress = bridgeHost.ifBlank { "127.0.0.1" },
                        port = item.listenPort,
                        publicKeyBase64 = item.publicKeyBase64,
                        fingerprint = CryptoEngine.formatFingerprintFromBase64(item.publicKeyBase64),
                        source = sourceText,
                        viaBridgePeerId = bridgePeerId,
                        bridgeHost = bridgeHost.ifBlank { "127.0.0.1" },
                        bridgePort = bridgePort
                    )
                }
            }
            mutable
        }
    }

    fun clearBridgedPeersFromBridge(bridgePeerId: String) {
        _discoveredPeers.update { current ->
            current.filterValues { it.viaBridgePeerId != bridgePeerId }
        }
    }

    fun clearAllBridgedPeers() {
        _discoveredPeers.update { current ->
            current.filterValues { it.viaBridgePeerId == null }
        }
    }

    fun buildDiscoveryReplyBytes(identity: LocalIdentity): ByteArray {
        val beacon = buildBeaconJson(identity, "ANNOUNCE") ?: return ByteArray(0)
        return beacon.toByteArray(Charsets.UTF_8)
    }

    private fun buildBeaconJson(identity: LocalIdentity, type: String): String? {
        if (!identity.isInitialized || !identity.isDiscoverable) return null
        return JSONObject().apply {
            put("magic", UDP_MAGIC)
            put("type", type)
            put("peerId", identity.peerId)
            put("name", identity.username)
            put("port", identity.listenPort)
            put("pub", identity.publicKeyBase64)
            put("disc", true)
            if (identity.bridgeEnabled && type == "ANNOUNCE") {
                val bridged = bridgedPeersProvider()
                    .filter {
                        it.isDiscoverable &&
                            it.peerId != identity.peerId &&
                            it.publicKeyBase64.isNotBlank()
                    }
                if (bridged.isNotEmpty()) {
                    val arr = JSONArray()
                    bridged.forEach { p ->
                        arr.put(
                            JSONObject().apply {
                                put("peerId", p.peerId)
                                put("name", p.username)
                                put("pub", p.publicKeyBase64)
                                put("port", p.listenPort)
                                put("disc", true)
                            }
                        )
                    }
                    put("bridgedPeers", arr)
                }
            }
        }.toString()
    }

    private fun registerNsdService(identity: LocalIdentity) {
        unregisterNsdService()
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "Chatso_${identity.peerId.take(8)}"
                serviceType = NSD_SERVICE_TYPE
                port = identity.listenPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            registrationListener = listener
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNsdService() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (_: Exception) {
        }
    }

    private fun startNsdBrowse() {
        stopNsdBrowse()
        try {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_chatso")) return
                    if (service.serviceName.endsWith(latestIdentity.peerId.take(8))) return
                    try {
                        @Suppress("DEPRECATION")
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val resolvedAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    runCatching {
                                        info.hostAddresses.firstOrNull { it is Inet4Address }
                                            ?: info.hostAddresses.firstOrNull()
                                    }.getOrNull() ?: info.host
                                } else {
                                    info.host
                                }
                                val rawIp = resolvedAddr?.hostAddress ?: return
                                val hostIp = NetworkUtils.normalizeHost(rawIp)
                                val badge = if (NetworkUtils.isLoopbackHost(hostIp) || NetworkUtils.isEmulatorHost(hostIp)) {
                                    EndpointKind.LOCALHOST.badge
                                } else {
                                    "mDNS"
                                }
                                scope.launch(Dispatchers.IO) {
                                    probeSingleHost(hostIp, info.port, badge)
                                }
                            }
                        })
                    } catch (_: Exception) {
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            discoveryListener = listener
            nsdManager?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun stopNsdBrowse() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stopAdvertising()
        stopNsdBrowse()
    }
}
