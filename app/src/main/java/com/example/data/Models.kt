package com.example.data

import org.json.JSONObject

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppLanguage(val code: String, val nativeName: String) {
    SYSTEM("system", "System"),
    PT("pt", "Português"),
    EN("en", "English"),
    ES("es", "Español"),
    FR("fr", "Français"),
    DE("de", "Deutsch");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    FILE,
    SYSTEM
}

enum class DeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    CANCELLED
}

enum class PeerPresence {
    OFFLINE,
    BACKGROUND,
    ONLINE
}

enum class SelfDestructOption(val seconds: Int) {
    OFF(0),
    SEC_30(30),
    MIN_5(300),
    HOUR_1(3600);

    companion object {
        fun fromSeconds(seconds: Int): SelfDestructOption =
            entries.firstOrNull { it.seconds == seconds } ?: OFF
    }
}

data class ActiveTransferInfo(
    val messageId: String,
    val peerId: String,
    val fileName: String,
    val progress: Int,
    val isOutgoing: Boolean,
    val totalBytes: Long
)

data class ReactionChipInfo(
    val emoji: String,
    val count: Int,
    val byMe: Boolean
)

object ReplySenderTags {
    const val ME = "__ME__"
    const val PEER = "__PEER__"
}

object ReactionCodec {
    val quickEmojis: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    fun parse(reactionsJson: String): Pair<String?, String?> {
        if (reactionsJson.isBlank()) return null to null
        return try {
            val obj = JSONObject(reactionsJson)
            val me = obj.optString("me").takeIf { it.isNotBlank() }
            val peer = obj.optString("peer").takeIf { it.isNotBlank() }
            me to peer
        } catch (_: Exception) {
            null to null
        }
    }

    fun encode(myEmoji: String?, peerEmoji: String?): String {
        val cleanMe = myEmoji?.takeIf { it.isNotBlank() }
        val cleanPeer = peerEmoji?.takeIf { it.isNotBlank() }
        if (cleanMe == null && cleanPeer == null) return ""
        return JSONObject().apply {
            if (cleanMe != null) put("me", cleanMe)
            if (cleanPeer != null) put("peer", cleanPeer)
        }.toString()
    }

    fun toggleMyReaction(reactionsJson: String, tappedEmoji: String): Pair<String, String> {
        val (currentMe, currentPeer) = parse(reactionsJson)
        val nextMe = if (currentMe == tappedEmoji) null else tappedEmoji
        val nextJson = encode(nextMe, currentPeer)
        return nextJson to (nextMe ?: "")
    }

    fun applyPeerReaction(reactionsJson: String, emoji: String): String {
        val (currentMe, _) = parse(reactionsJson)
        val nextPeer = emoji.takeIf { it.isNotBlank() }
        return encode(currentMe, nextPeer)
    }

    fun groupedChips(reactionsJson: String): List<ReactionChipInfo> {
        val (me, peer) = parse(reactionsJson)
        if (me == null && peer == null) return emptyList()
        if (me != null && me == peer) {
            return listOf(ReactionChipInfo(emoji = me, count = 2, byMe = true))
        }
        return buildList {
            if (me != null) add(ReactionChipInfo(emoji = me, count = 1, byMe = true))
            if (peer != null) add(ReactionChipInfo(emoji = peer, count = 1, byMe = false))
        }
    }
}

data class LocalIdentity(
    val isInitialized: Boolean = false,
    val username: String = "",
    val avatarPath: String? = null,
    val publicKeyBase64: String = "",
    val wrappedPrivateKeyBase64: String = "",
    val peerId: String = "",
    val fingerprint: String = "",
    val listenPort: Int = DEFAULT_TCP_PORT,
    val isDiscoverable: Boolean = true,
    val blockUnknownPeers: Boolean = false,
    val qrInviteToken: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val backgroundConnectionEnabled: Boolean = true,
    val batteryPromptDismissed: Boolean = false
) {
    companion object {
        const val DEFAULT_TCP_PORT = 42850
        const val DISCOVERY_UDP_PORT = 42851
    }
}

data class NetworkEndpointInfo(
    val interfaceName: String,
    val ipAddress: String,
    val kind: EndpointKind
)

enum class EndpointKind(val badge: String) {
    VPN("VPN"),
    LAN("Wi-Fi / LAN"),
    LOCALHOST("Localhost")
}

data class DiscoveredPeer(
    val peerId: String,
    val username: String,
    val ipAddress: String,
    val port: Int,
    val publicKeyBase64: String,
    val fingerprint: String,
    val source: String,
    val lastSeenAt: Long = System.currentTimeMillis()
)
