package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.example.crypto.CryptoEngine
import com.example.data.DiscoveredPeer
import com.example.data.EndpointKind
import com.example.data.LocalIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.json.JSONObject

/**
 * Handles LAN & VPN peer discovery while strictly honoring Invisible Mode (`isDiscoverable == false`).
 * When `isDiscoverable` is false:
 * - Zero NSD/mDNS service announcements are registered.
 * - Zero UDP discovery beacons or replies are transmitted.
 */
class DiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
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

    private var udpResponderJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun applyIdentityPolicy(identity: LocalIdentity) {
        latestIdentity = identity
        if (!identity.isInitialized || !identity.isDiscoverable) {
            // Strict Invisible Mode: immediately tear down all network announcements
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
                    val replyBytes = buildBeaconJson(current, "ANNOUNCE").toByteArray(Charsets.UTF_8)
                    val replyPacket = DatagramPacket(
                        replyBytes,
                        replyBytes.size,
                        packet.address,
                        packet.port
                    )
                    socket.send(replyPacket)
                } else if (type == "ANNOUNCE") {
                    handleIncomingBeacon(json, packet.address.hostAddress ?: "", "UDP LAN")
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
        if (_isScanning.value) return
        val identity = latestIdentity
        if (!identity.isInitialized) return

        scope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                startNsdBrowse()
                kotlinx.coroutines.withTimeoutOrNull(2500L) {
                    coroutineScope {
                        // 1. Immediately probe known peers (<150ms)
                        if (knownTargets.isNotEmpty()) {
                            launch {
                                knownTargets.distinct().forEach { (ip, port) ->
                                    if (ip.isNotBlank()) {
                                        launch { probeSingleHost(ip, port, "Saved") }
                                    }
                                }
                            }
                        }
                        // 2. UDP LAN broadcast query
                        launch { sendUdpQuery(identity) }
                        // 3. Fast concurrent subnet probe
                        launch { probeActiveSubnets(identity) }
                        // Keep mDNS browse active briefly within the 2.5s window
                        launch { delay(1800L) }
                    }
                }
            } finally {
                stopNsdBrowse()
                _isScanning.value = false
            }
        }
    }

    private fun sendUdpQuery(identity: LocalIdentity) {
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 450
                // When in Invisible Mode, do not include username or publicKey in query packet
                val queryJson = JSONObject().apply {
                    put("magic", UDP_MAGIC)
                    put("type", "QUERY")
                    put("peerId", if (identity.isDiscoverable) identity.peerId else "anon")
                }.toString().toByteArray(Charsets.UTF_8)

                val targets = mutableSetOf("255.255.255.255")
                NetworkUtils.getActiveEndpoints()
                    .filter { it.kind != EndpointKind.LOCALHOST }
                    .mapNotNull { NetworkUtils.subnetPrefix24(it.ipAddress) }
                    .forEach { targets.add("$it.255") }

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

                // Collect replies for up to 550ms
                val endAt = System.currentTimeMillis() + 550L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < endAt) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        sock.receive(resp)
                        val json = JSONObject(String(resp.data, 0, resp.length, Charsets.UTF_8))
                        if (json.optString("magic") == UDP_MAGIC && json.optString("type") == "ANNOUNCE") {
                            handleIncomingBeacon(json, resp.address.hostAddress ?: "", "LAN")
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun probeActiveSubnets(identity: LocalIdentity) = withContext(Dispatchers.IO) {
        val endpoints = NetworkUtils.getActiveEndpoints()
            .filter { it.kind == EndpointKind.VPN || it.kind == EndpointKind.LAN }
            .take(2)
        val semaphore = Semaphore(64)
        val targetPort = identity.listenPort

        coroutineScope {
            for (ep in endpoints) {
                val prefix = NetworkUtils.subnetPrefix24(ep.ipAddress) ?: continue
                (1..254).forEach { hostOctet ->
                    val candidateIp = "$prefix.$hostOctet"
                    if (candidateIp != ep.ipAddress) {
                        launch {
                            semaphore.withPermit {
                                probeSingleHost(candidateIp, targetPort, ep.kind.badge)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun probeSingleHost(ip: String, port: Int, badge: String) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 120)
                socket.soTimeout = 200
                val out = DataOutputStream(socket.getOutputStream())
                val inp = DataInputStream(socket.getInputStream())
                out.writeByte(KIND_DISCOVERY_PROBE.toInt())
                out.writeInt(0)
                out.flush()

                val replyKind = inp.readByte()
                if (replyKind != KIND_DISCOVERY_REPLY) return
                val len = inp.readInt()
                if (len <= 0 || len > 16_384) return
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                handleIncomingBeacon(json, ip, badge)
            }
        } catch (_: Exception) {
            // Unreachable or peer is in Invisible Mode (socket closed silently)
        }
    }

    private fun handleIncomingBeacon(json: JSONObject, sourceIp: String, sourceLabel: String) {
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
        _discoveredPeers.update { it + (peerId to peer) }
    }

    fun buildDiscoveryReplyBytes(identity: LocalIdentity): ByteArray {
        return buildBeaconJson(identity, "ANNOUNCE").toByteArray(Charsets.UTF_8)
    }

    private fun buildBeaconJson(identity: LocalIdentity, type: String): String {
        return JSONObject().apply {
            put("magic", UDP_MAGIC)
            put("type", type)
            put("peerId", identity.peerId)
            put("name", identity.username)
            put("port", identity.listenPort)
            put("pub", identity.publicKeyBase64)
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
                                val hostIp = info.host?.hostAddress ?: return
                                scope.launch(Dispatchers.IO) {
                                    probeSingleHost(hostIp, info.port, "mDNS")
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
