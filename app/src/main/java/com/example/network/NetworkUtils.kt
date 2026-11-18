package com.example.network

import android.content.Context
import android.content.Intent
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

object NetworkUtils {

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

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
        return results.sortedBy {
            when (it.kind) {
                EndpointKind.VPN -> 0
                EndpointKind.LAN -> 1
                EndpointKind.LOCALHOST -> 2
            }
        }
    }

    fun getPreferredShareIp(): String {
        val endpoints = getActiveEndpoints()
        return endpoints.firstOrNull { it.kind == EndpointKind.VPN }?.ipAddress
            ?: endpoints.firstOrNull { it.kind == EndpointKind.LAN }?.ipAddress
            ?: "127.0.0.1"
    }

    private fun classifyEndpoint(ifName: String, ip: String, isLoopback: Boolean): EndpointKind {
        if (isLoopback || ip.startsWith("127.")) return EndpointKind.LOCALHOST
        val vpnPrefixes = listOf("tun", "wg", "ppp", "utun", "tailscale", "zt", "tap", "ipsec")
        if (vpnPrefixes.any { ifName.startsWith(it) }) return EndpointKind.VPN
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
