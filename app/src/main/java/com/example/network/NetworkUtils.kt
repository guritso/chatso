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

object NetworkUtils {

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    private val vpnPrefixes = listOf("tun", "wg", "ppp", "utun", "tailscale", "zt", "tap", "ipsec")
    private val cellularPrefixes = listOf("rmnet", "ccmni", "pdp", "wwan", "seth", "clat")

    fun getActiveEndpoints(): List<NetworkEndpointInfo> {
        val results = mutableListOf<NetworkEndpointInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return fallbackLoopback()
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp) continue
                val name = nif.name.lowercase(Locale.ROOT)
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
        } catch (_: Exception) {
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
                            val broadcastIp = if (isLoopback) {
                                null
                            } else {
                                (ifAddr.broadcast as? Inet4Address)?.hostAddress
                                    ?: subnetPrefix24(ip)?.let { "$it.255" }
                            }
                            results.add(
                                NetworkScanTarget(
                                    interfaceName = nif.name,
                                    ipAddress = ip,
                                    kind = kind,
                                    prefixLength = ifAddr.networkPrefixLength,
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

    fun findAndroidNetworkForIp(context: Context, hostIp: String): Network? {
        val clean = hostIp.trim()
        if (clean.isBlank() ||
            clean == "localhost" ||
            clean.startsWith("127.") ||
            clean == "10.0.2.2"
        ) {
            return null
        }
        val targets = getActiveNetworkScanTargets(context)
            .filter { it.kind != EndpointKind.LOCALHOST && it.androidNetwork != null }
        // Exact interface subnet match first (preferring LAN for local LAN IPs so VPN doesn't hijack)
        val exactMatch = targets
            .sortedBy { if (it.kind == EndpointKind.LAN) 0 else 1 }
            .firstOrNull { target ->
                val effectivePrefix = when {
                    target.kind == EndpointKind.VPN && target.prefixLength >= 32 -> 24
                    target.prefixLength <= 0 -> 24
                    else -> target.prefixLength.toInt()
                }
                ipInSubnet(clean, target.ipAddress, effectivePrefix)
            }
        return exactMatch?.androidNetwork
    }

    fun classifyIpKind(ip: String, targets: List<NetworkScanTarget>): EndpointKind? {
        val clean = ip.trim()
        if (clean.isBlank()) return null
        if (clean == "localhost" || clean.startsWith("127.") || clean == "10.0.2.2") {
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
