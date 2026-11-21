package com.example.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.data.EndpointKind
import com.example.data.NetworkEndpointInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

data class NetworkScanTarget(
    val interfaceName: String,
    val ipAddress: String,
    val kind: EndpointKind,
    val prefixLength: Short = 24,
    val broadcastAddress: String? = null,
    val androidNetwork: Network? = null,
    val isCellular: Boolean = false
)

data class TcpSocketAttempt(
    val androidNetwork: Network? = null,
    val bindLocalLoopback: Boolean = false
)

object NetworkUtils {

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    private val vpnPrefixes = listOf("tun", "wg", "ppp", "utun", "tailscale", "zt", "tap", "ipsec")
    private val cellularPrefixes = listOf(
        "rmnet", "ccmni", "pdp", "wwan", "seth", "clat",
        "v4-rmnet", "v4-ccmni", "v4-pdp", "v4-wwan"
    )
    private val ignoredVirtualPrefixes = listOf("dummy", "sit", "ip_vti", "ip6tnl", "gre")

    fun normalizeHost(host: String): String {
        val trimmed = host.trim()
        return when {
            trimmed.equals("localhost", ignoreCase = true) || trimmed == "::1" -> "127.0.0.1"
            trimmed.startsWith("::ffff:", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }
    }

    fun isLoopbackHost(host: String): Boolean {
        val clean = normalizeHost(host)
        return clean.startsWith("127.")
    }

    fun isEmulatorHost(host: String): Boolean = normalizeHost(host) == "10.0.2.2"

    fun isPrivateLanIp(host: String): Boolean {
        val clean = normalizeHost(host)
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts.none { it !in 0..255 }) {
            return when {
                parts[0] == 10 -> true
                parts[0] == 172 && parts[1] in 16..31 -> true
                parts[0] == 192 && parts[1] == 168 -> true
                else -> false
            }
        }
        return isIpv6LinkLocalOrUla(clean)
    }

    fun isIpv6LinkLocalOrUla(host: String): Boolean {
        val lower = normalizeHost(host).substringBefore('%').lowercase(Locale.ROOT)
        if (!lower.contains(':')) return false
        return lower.startsWith("fe80:") ||
            lower.startsWith("fc") ||
            lower.startsWith("fd")
    }

    fun isAlwaysLocalLanIp(host: String): Boolean {
        val clean = normalizeHost(host)
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts.none { it !in 0..255 }) {
            return (parts[0] == 192 && parts[1] == 168) || (parts[0] == 169 && parts[1] == 254)
        }
        return isIpv6LinkLocalOrUla(clean)
    }

    fun computeBroadcastIp(ip: String, prefixLength: Short): String? {
        val clean = normalizeHost(ip)
        val pfx = prefixLength.toInt()
        if (clean.startsWith("127.") || pfx !in 8..30) return null
        val ipInt = ipv4ToInt(clean) ?: return null
        val mask = -1 shl (32 - pfx)
        val bcast = ipInt or mask.inv()
        return "${(bcast ushr 24) and 0xFF}.${(bcast ushr 16) and 0xFF}.${(bcast ushr 8) and 0xFF}.${bcast and 0xFF}"
    }

    @Suppress("DEPRECATION")
    fun getActiveEndpoints(context: Context? = null): List<NetworkEndpointInfo> {
        val results = mutableListOf<NetworkEndpointInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (nif in Collections.list(interfaces)) {
                    if (!nif.isUp) continue
                    val name = nif.name.lowercase(Locale.ROOT)
                    if (ignoredVirtualPrefixes.any { name.startsWith(it) }) continue
                    for (addr in Collections.list(nif.inetAddresses)) {
                        if (addr !is Inet4Address) continue
                        val ip = addr.hostAddress ?: continue
                        val kind = classifyEndpoint(name, ip, addr.isLoopbackAddress)
                        results.add(
                            NetworkEndpointInfo(
                                interfaceName = nif.name,
                                ipAddress = ip,
                                kind = kind
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (context != null) {
            val cm = runCatching {
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull()
            if (cm != null) {
                runCatching {
                    val seenIps = results.mapTo(HashSet()) { it.ipAddress }
                    for (network in cm.allNetworks) {
                        val caps = cm.getNetworkCapabilities(network) ?: continue
                        val lp = cm.getLinkProperties(network) ?: continue
                        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        val ifName = lp.interfaceName?.takeIf { it.isNotBlank() }
                            ?: if (isVpn) "tun" else "net"
                        val lowerIf = ifName.lowercase(Locale.ROOT)
                        for (linkAddr in lp.linkAddresses) {
                            val v4 = linkAddr.address as? Inet4Address ?: continue
                            val ip = v4.hostAddress ?: continue
                            if (seenIps.contains(ip)) continue
                            val isLoopback = v4.isLoopbackAddress || ip.startsWith("127.")
                            val kind = when {
                                isLoopback -> EndpointKind.LOCALHOST
                                isVpn -> EndpointKind.VPN
                                else -> classifyEndpoint(lowerIf, ip, isLoopback)
                            }
                            results.add(
                                NetworkEndpointInfo(
                                    interfaceName = ifName,
                                    ipAddress = ip,
                                    kind = kind
                                )
                            )
                            seenIps.add(ip)
                        }
                    }
                }
            }
        }
        if (results.none { it.kind == EndpointKind.LOCALHOST }) {
            results.add(NetworkEndpointInfo("lo", "127.0.0.1", EndpointKind.LOCALHOST))
        }
        return results
            .distinctBy { it.interfaceName to it.ipAddress }
            .sortedBy {
                when (it.kind) {
                    EndpointKind.VPN -> 0
                    EndpointKind.LAN -> 1
                    EndpointKind.LOCALHOST -> 2
                }
            }
    }

    @Suppress("DEPRECATION")
    fun hasActiveVpn(context: Context): Boolean {
        val cm = runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
        val hasCmVpn = if (cm != null) {
            runCatching {
                cm.allNetworks.any { network ->
                    cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }.getOrDefault(false)
        } else {
            false
        }
        if (hasCmVpn) return true
        return getActiveEndpoints().any { it.kind == EndpointKind.VPN }
    }

    @Suppress("DEPRECATION")
    fun getNonVpnNetworks(context: Context, preferLanOnly: Boolean = false): List<Network> {
        val cm = runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return emptyList()

        return runCatching {
            cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!isEthernet && !isWifi && !isCellular && !hasInternet) return@mapNotNull null
                if (preferLanOnly && isCellular && !isEthernet && !isWifi) return@mapNotNull null
                val priority = when {
                    isEthernet -> 0
                    isWifi -> 1
                    !isCellular -> 2
                    else -> 3
                }
                priority to network
            }.sortedBy { it.first }.map { it.second }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    fun getActiveNetworkScanTargets(context: Context): List<NetworkScanTarget> {
        val cm = runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

        data class CmNetworkMeta(
            val network: Network,
            val isVpn: Boolean,
            val isCellular: Boolean
        )

        val metaByIfName = HashMap<String, CmNetworkMeta>()
        val metaByIp = HashMap<String, CmNetworkMeta>()

        if (cm != null) {
            runCatching {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val lp = cm.getLinkProperties(network) ?: continue
                    val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isVpn
                    val meta = CmNetworkMeta(network = network, isVpn = isVpn, isCellular = isCellular)
                    lp.interfaceName?.lowercase(Locale.ROOT)?.let { metaByIfName[it] = meta }
                    for (linkAddr in lp.linkAddresses) {
                        val v4 = linkAddr.address as? Inet4Address ?: continue
                        v4.hostAddress?.let { metaByIp[it] = meta }
                    }
                }
            }
        }

        val results = mutableListOf<NetworkScanTarget>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (nif in Collections.list(interfaces)) {
                    if (!nif.isUp) continue
                    val lowerName = nif.name.lowercase(Locale.ROOT)
                    if (ignoredVirtualPrefixes.any { lowerName.startsWith(it) }) continue
                    val ifMeta = metaByIfName[lowerName]

                    val ifaceAddrs = runCatching { nif.interfaceAddresses }.getOrNull().orEmpty()
                    if (ifaceAddrs.isNotEmpty()) {
                        for (ifAddr in ifaceAddrs) {
                            val addr = ifAddr.address as? Inet4Address ?: continue
                            val ip = addr.hostAddress ?: continue
                            val ipMeta = metaByIp[ip] ?: ifMeta
                            val isLoopback = addr.isLoopbackAddress || ip.startsWith("127.")
                            val isCellular = !isLoopback && (
                                ipMeta?.isCellular == true || isCellularInterface(lowerName)
                                )
                            val kind = when {
                                isLoopback -> EndpointKind.LOCALHOST
                                ipMeta?.isVpn == true -> EndpointKind.VPN
                                else -> classifyEndpoint(lowerName, ip, isLoopback)
                            }
                            val pfx = ifAddr.networkPrefixLength
                            val broadcastIp = if (isLoopback) {
                                null
                            } else {
                                (ifAddr.broadcast as? Inet4Address)?.hostAddress
                                    ?: computeBroadcastIp(ip, pfx)
                                    ?: subnetPrefix24(ip)?.let { "$it.255" }
                            }
                            results.add(
                                NetworkScanTarget(
                                    interfaceName = nif.name,
                                    ipAddress = ip,
                                    kind = kind,
                                    prefixLength = pfx,
                                    broadcastAddress = broadcastIp,
                                    androidNetwork = if (isLoopback) null else ipMeta?.network,
                                    isCellular = isCellular
                                )
                            )
                        }
                    } else {
                        for (addr in Collections.list(nif.inetAddresses)) {
                            if (addr !is Inet4Address) continue
                            val ip = addr.hostAddress ?: continue
                            val ipMeta = metaByIp[ip] ?: ifMeta
                            val isLoopback = addr.isLoopbackAddress || ip.startsWith("127.")
                            val isCellular = !isLoopback && (
                                ipMeta?.isCellular == true || isCellularInterface(lowerName)
                                )
                            val kind = when {
                                isLoopback -> EndpointKind.LOCALHOST
                                ipMeta?.isVpn == true -> EndpointKind.VPN
                                else -> classifyEndpoint(lowerName, ip, isLoopback)
                            }
                            results.add(
                                NetworkScanTarget(
                                    interfaceName = nif.name,
                                    ipAddress = ip,
                                    kind = kind,
                                    prefixLength = 24,
                                    broadcastAddress = if (isLoopback) null else subnetPrefix24(ip)?.let { "$it.255" },
                                    androidNetwork = if (isLoopback) null else ipMeta?.network,
                                    isCellular = isCellular
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Fallback & enrichment from ConnectivityManager LinkProperties: on Android 11+ (SELinux)
        // NetworkInterface.getNetworkInterfaces() may omit tun0 or strip wlan0 interfaceAddresses.
        if (cm != null) {
            runCatching {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val lp = cm.getLinkProperties(network) ?: continue
                    val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isVpn
                    val ifName = lp.interfaceName?.takeIf { it.isNotBlank() }
                        ?: if (isVpn) "tun" else "net"
                    val lowerIf = ifName.lowercase(Locale.ROOT)
                    if (ignoredVirtualPrefixes.any { lowerIf.startsWith(it) }) continue
                    for (linkAddr in lp.linkAddresses) {
                        val v4 = linkAddr.address as? Inet4Address ?: continue
                        val ip = v4.hostAddress ?: continue
                        val isLoopback = v4.isLoopbackAddress || ip.startsWith("127.")
                        val kind = when {
                            isLoopback -> EndpointKind.LOCALHOST
                            isVpn -> EndpointKind.VPN
                            else -> classifyEndpoint(lowerIf, ip, isLoopback)
                        }
                        val pfx = linkAddr.prefixLength.toShort()
                        val computedBcast = if (isLoopback) {
                            null
                        } else {
                            computeBroadcastIp(ip, pfx) ?: subnetPrefix24(ip)?.let { "$it.255" }
                        }
                        val existingIdx = results.indexOfFirst { it.ipAddress == ip }
                        if (existingIdx >= 0) {
                            val existing = results[existingIdx]
                            if (existing.androidNetwork == null && !isLoopback) {
                                results[existingIdx] = existing.copy(
                                    androidNetwork = network,
                                    prefixLength = if (pfx in 1..32) pfx else existing.prefixLength,
                                    broadcastAddress = existing.broadcastAddress ?: computedBcast,
                                    kind = if (isVpn) EndpointKind.VPN else existing.kind,
                                    isCellular = existing.isCellular || isCellular
                                )
                            }
                        } else {
                            results.add(
                                NetworkScanTarget(
                                    interfaceName = ifName,
                                    ipAddress = ip,
                                    kind = kind,
                                    prefixLength = if (pfx in 1..32) pfx else 24,
                                    broadcastAddress = computedBcast,
                                    androidNetwork = if (isLoopback) null else network,
                                    isCellular = isCellular
                                )
                            )
                        }
                    }
                }
            }
        }

        if (results.none { it.kind == EndpointKind.LOCALHOST }) {
            results.add(
                NetworkScanTarget(
                    interfaceName = "lo",
                    ipAddress = "127.0.0.1",
                    kind = EndpointKind.LOCALHOST,
                    prefixLength = 8,
                    broadcastAddress = null,
                    androidNetwork = null,
                    isCellular = false
                )
            )
        }
        return results
            .distinctBy { it.interfaceName to it.ipAddress }
            .sortedBy {
                when (it.kind) {
                    EndpointKind.LOCALHOST -> 0
                    EndpointKind.LAN -> 1
                    EndpointKind.VPN -> 2
                }
            }
    }

    fun findLocalIpForNetwork(context: Context, network: Network): String? {
        return getActiveNetworkScanTargets(context)
            .firstOrNull { it.androidNetwork == network }
            ?.ipAddress
    }

    fun isVpnNetwork(context: Context, network: Network?): Boolean {
        if (network == null) return false
        val cm = runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return false
        return runCatching {
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)
    }

    fun findAndroidNetworkForIp(context: Context, hostIp: String): Network? {
        val clean = normalizeHost(hostIp)
        if (clean.isBlank() || clean.startsWith("127.")) {
            return null
        }
        if (clean == "10.0.2.2") {
            return getNonVpnNetworks(context, preferLanOnly = true).firstOrNull()
                ?: getNonVpnNetworks(context, preferLanOnly = false).firstOrNull()
        }
        if (isIpv6LinkLocalOrUla(clean)) {
            return getNonVpnNetworks(context, preferLanOnly = true).firstOrNull()
        }
        val targets = getActiveNetworkScanTargets(context)
            .filter { it.kind == EndpointKind.LAN && !it.isCellular && it.androidNetwork != null }
        // Exact LAN interface subnet match (returns physical non-VPN Network so sockets bypass WireGuard 0.0.0.0/0)
        val exactMatch = targets.firstOrNull { target ->
            val effectivePrefix = if (target.prefixLength <= 0 || target.prefixLength >= 32) {
                24
            } else {
                target.prefixLength.toInt()
            }
            ipInSubnet(clean, target.ipAddress, effectivePrefix)
        }
        return exactMatch?.androidNetwork
    }

    fun isIpInActiveVpnSubnet(context: Context, hostIp: String): Boolean {
        val clean = normalizeHost(hostIp)
        if (clean.isBlank()) return false
        val vpnTargets = getActiveNetworkScanTargets(context)
            .filter { it.kind == EndpointKind.VPN }
        return vpnTargets.any { target ->
            val effectivePrefix = when {
                target.prefixLength >= 32 -> 24
                target.prefixLength <= 0 -> 24
                else -> target.prefixLength.toInt()
            }
            ipInSubnet(clean, target.ipAddress, effectivePrefix)
        }
    }

    fun buildTcpSocketAttempts(
        context: Context,
        host: String,
        preferredNetwork: Network? = null
    ): List<TcpSocketAttempt> {
        val clean = normalizeHost(host)
        val vpnActive = hasActiveVpn(context)

        if (isLoopbackHost(clean)) {
            val nonVpnNets = if (vpnActive) getNonVpnNetworks(context) else emptyList()
            return buildList {
                if (vpnActive) {
                    // Bypass VPN fwmark first when an underlying non-VPN network exists,
                    // then fall back to explicit local 127.0.0.1 bind and plain unbound Socket.
                    nonVpnNets.take(2).forEach { net ->
                        add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = false))
                    }
                    add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = true))
                    add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = false))
                    nonVpnNets.firstOrNull()?.let { net ->
                        add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = true))
                    }
                } else {
                    add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = false))
                    add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = true))
                }
            }.distinct()
        }

        if (isEmulatorHost(clean)) {
            val nonVpnNets = getNonVpnNetworks(context)
            return buildList {
                nonVpnNets.take(2).forEach { net ->
                    add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = false))
                }
                add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = false))
            }.distinct()
        }

        val preferredIsVpn = isVpnNetwork(context, preferredNetwork)
        val matched = preferredNetwork?.takeIf { !preferredIsVpn } ?: findAndroidNetworkForIp(context, clean)
        val inVpnSubnet = preferredIsVpn || isIpInActiveVpnSubnet(context, clean)

        return buildList {
            if (matched != null) {
                add(TcpSocketAttempt(androidNetwork = matched, bindLocalLoopback = false))
                if (vpnActive) {
                    // Try any other physical LAN network before falling back to system/VPN route
                    getNonVpnNetworks(context, preferLanOnly = true).take(2).forEach { net ->
                        add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = false))
                    }
                }
            } else if (vpnActive && isAlwaysLocalLanIp(clean) && !inVpnSubnet) {
                // Local 192.168.x.x / 169.254.x.x / fe80:: addresses should bypass full-tunnel VPN (e.g. WireGuard 0.0.0.0/0) first
                getNonVpnNetworks(context, preferLanOnly = true).take(2).forEach { net ->
                    add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = false))
                }
            }

            // Default system routing (routes VPN destinations through tun0 automatically without bindSocket restrictions)
            add(TcpSocketAttempt(androidNetwork = null, bindLocalLoopback = false))

            if (matched == null && vpnActive && isPrivateLanIp(clean) && !inVpnSubnet) {
                getNonVpnNetworks(context, preferLanOnly = true).take(2).forEach { net ->
                    add(TcpSocketAttempt(androidNetwork = net, bindLocalLoopback = false))
                }
            }
            if (preferredNetwork != null && preferredIsVpn) {
                add(TcpSocketAttempt(androidNetwork = preferredNetwork, bindLocalLoopback = false))
            }
        }.distinct()
    }

    fun openConnectedTcpSocket(
        context: Context,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        preferredNetwork: Network? = null,
        allowFallback: Boolean = true
    ): java.net.Socket {
        val clean = normalizeHost(host)
        val attempts = if (!allowFallback && preferredNetwork != null && !isVpnNetwork(context, preferredNetwork)) {
            listOf(TcpSocketAttempt(androidNetwork = preferredNetwork, bindLocalLoopback = false))
        } else {
            buildTcpSocketAttempts(context, clean, preferredNetwork)
        }
        var lastError: Exception? = null
        for (attempt in attempts) {
            val socket = java.net.Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                if (attempt.androidNetwork != null) {
                    attempt.androidNetwork.bindSocket(socket)
                }
                if (attempt.bindLocalLoopback) {
                    socket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                }
                socket.connect(java.net.InetSocketAddress(clean, port), connectTimeoutMs)
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("Unable to connect to $clean:$port")
    }

    fun classifyIpKind(ip: String, targets: List<NetworkScanTarget>): EndpointKind? {
        val clean = normalizeHost(ip)
        if (clean.isBlank()) return null
        if (clean.startsWith("127.") || clean == "10.0.2.2") {
            return EndpointKind.LOCALHOST
        }
        val matched = targets
            .filter { it.kind != EndpointKind.LOCALHOST }
            .firstOrNull { target ->
                val effectivePrefix = when {
                    target.kind == EndpointKind.VPN && target.prefixLength >= 32 -> 24
                    target.prefixLength <= 0 -> 24
                    else -> target.prefixLength.toInt()
                }
                ipInSubnet(clean, target.ipAddress, effectivePrefix)
            }
        if (matched != null) return matched.kind
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts[0] == 100 && parts[1] in 64..127) {
            return EndpointKind.VPN
        }
        return null
    }

    fun ipInSubnet(ipA: String, ipB: String, prefixLength: Int): Boolean {
        val a = ipv4ToInt(ipA) ?: return false
        val b = ipv4ToInt(ipB) ?: return false
        val clamped = prefixLength.coerceIn(0, 32)
        val mask = if (clamped == 0) 0 else (-1 shl (32 - clamped))
        return (a and mask) == (b and mask)
    }

    private fun ipv4ToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            acc = (acc shl 8) or v
        }
        return acc
    }

    fun getPreferredShareIp(): String {
        val endpoints = getActiveEndpoints()
        return endpoints.firstOrNull { it.kind == EndpointKind.VPN }?.ipAddress
            ?: endpoints.firstOrNull { it.kind == EndpointKind.LAN }?.ipAddress
            ?: "127.0.0.1"
    }

    private fun isCellularInterface(ifName: String): Boolean {
        return cellularPrefixes.any { ifName.startsWith(it) }
    }

    private fun classifyEndpoint(ifName: String, ip: String, isLoopback: Boolean): EndpointKind {
        if (isLoopback || ip.startsWith("127.")) return EndpointKind.LOCALHOST
        if (vpnPrefixes.any { ifName.startsWith(it) }) return EndpointKind.VPN
        if (isCellularInterface(ifName)) return EndpointKind.LAN
        // Carrier-Grade NAT range 100.64.0.0/10 commonly used by Tailscale / WireGuard meshes
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts[0] == 100 && parts[1] in 64..127) {
            return EndpointKind.VPN
        }
        return EndpointKind.LAN
    }

    private fun fallbackLoopback(): List<NetworkEndpointInfo> = listOf(
        NetworkEndpointInfo("lo", "127.0.0.1", EndpointKind.LOCALHOST)
    )

    fun subnetPrefix24(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4 || ip.startsWith("127.")) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    fun formatDurationMs(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    fun formatEtaSeconds(seconds: Long?): String? {
        if (seconds == null || seconds <= 0L) return null
        return when {
            seconds < 60L -> "~${seconds}s"
            seconds < 3600L -> {
                val mins = seconds / 60L
                val remSecs = seconds % 60L
                if (remSecs == 0L) "~${mins}m" else "~${mins}m ${remSecs}s"
            }
            else -> {
                val hours = seconds / 3600L
                val remMins = (seconds % 3600L) / 60L
                if (remMins == 0L) "~${hours}h" else "~${hours}h ${remMins}m"
            }
        }
    }

    fun formatTimestamp(epochMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - epochMs
        val fmt = if (diff < 24 * 3600 * 1000L) timeFormat.get() else dateTimeFormat.get()
        return fmt?.format(Date(epochMs)) ?: ""
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryOptimizationRequest(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }
}
