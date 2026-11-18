package com.example.data

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
    VOICE,
    FILE,
    SYSTEM
}

enum class DeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
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
