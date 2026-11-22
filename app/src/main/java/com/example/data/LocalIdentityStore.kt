package com.example.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.crypto.CryptoEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chatso_local_identity"
)

class LocalIdentityStore(
    private val context: Context,
    private val dataStoreOverride: DataStore<Preferences>? = null
) {
    private val store: DataStore<Preferences>
        get() = dataStoreOverride ?: context.identityDataStore
    private object Keys {
        val INITIALIZED = booleanPreferencesKey("initialized")
        val USERNAME = stringPreferencesKey("username")
        val AVATAR_PATH = stringPreferencesKey("avatar_path")
        val PUBLIC_KEY = stringPreferencesKey("public_key_b64")
        val WRAPPED_PRIVATE_KEY = stringPreferencesKey("wrapped_private_key_b64")
        val PEER_ID = stringPreferencesKey("peer_id")
        val FINGERPRINT = stringPreferencesKey("fingerprint")
        val LISTEN_PORT = intPreferencesKey("listen_port")
        val IS_DISCOVERABLE = booleanPreferencesKey("is_discoverable")
        val BLOCK_UNKNOWN_PEERS = booleanPreferencesKey("block_unknown_peers")
        val QR_INVITE_TOKEN = stringPreferencesKey("qr_invite_token")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
        val THEME_ADVANCED = booleanPreferencesKey("theme_advanced")
        val LANGUAGE = stringPreferencesKey("app_language")
        val BACKGROUND_CONN_ENABLED = booleanPreferencesKey("background_connection_enabled")
        val BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_prompt_dismissed")
        val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")
        val BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
        val INSTANCE_ID = stringPreferencesKey("instance_id")
    }

    val identityFlow: Flow<LocalIdentity> = store.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val theme = ThemeMode.entries.firstOrNull { it.name == themeStr } ?: ThemeMode.SYSTEM
        val palette = ThemePalette.fromName(prefs[Keys.THEME_PALETTE])
        val lang = AppLanguage.fromCode(prefs[Keys.LANGUAGE])
        LocalIdentity(
            isInitialized = prefs[Keys.INITIALIZED] ?: false,
            username = prefs[Keys.USERNAME] ?: "",
            avatarPath = prefs[Keys.AVATAR_PATH],
            publicKeyBase64 = prefs[Keys.PUBLIC_KEY] ?: "",
            wrappedPrivateKeyBase64 = prefs[Keys.WRAPPED_PRIVATE_KEY] ?: "",
            peerId = prefs[Keys.PEER_ID] ?: "",
            fingerprint = prefs[Keys.FINGERPRINT] ?: "",
            listenPort = prefs[Keys.LISTEN_PORT] ?: LocalIdentity.DEFAULT_TCP_PORT,
            isDiscoverable = prefs[Keys.IS_DISCOVERABLE] ?: true,
            blockUnknownPeers = prefs[Keys.BLOCK_UNKNOWN_PEERS] ?: false,
            qrInviteToken = prefs[Keys.QR_INVITE_TOKEN] ?: generateInviteToken(),
            themeMode = theme,
            themePalette = palette,
            themeAdvanced = prefs[Keys.THEME_ADVANCED] ?: false,
            language = lang,
            backgroundConnectionEnabled = prefs[Keys.BACKGROUND_CONN_ENABLED] ?: true,
            batteryPromptDismissed = prefs[Keys.BATTERY_PROMPT_DISMISSED] ?: false,
            devModeEnabled = prefs[Keys.DEV_MODE_ENABLED] ?: false,
            bridgeEnabled = prefs[Keys.BRIDGE_ENABLED] ?: false,
            instanceId = prefs[Keys.INSTANCE_ID] ?: ""
        )
    }

    suspend fun ensureInstanceId(): String {
        var resolved = ""
        store.edit { prefs ->
            val current = prefs[Keys.INSTANCE_ID]
            resolved = if (current.isNullOrBlank()) {
                generateInstanceId().also { prefs[Keys.INSTANCE_ID] = it }
            } else {
                current
            }
        }
        return resolved
    }

    suspend fun getSnapshot(): LocalIdentity = identityFlow.first()

    suspend fun createIdentity(
        username: String,
        avatarPath: String?,
        isDiscoverable: Boolean,
        pregeneratedMaterial: CryptoEngine.IdentityKeyMaterial? = null
    ): LocalIdentity {
        val material = pregeneratedMaterial ?: CryptoEngine.createIdentityMaterial()
        val inviteToken = generateInviteToken()
        store.edit { prefs ->
            prefs[Keys.INITIALIZED] = true
            prefs[Keys.USERNAME] = username.trim()
            if (avatarPath != null) {
                prefs[Keys.AVATAR_PATH] = avatarPath
            } else {
                prefs.remove(Keys.AVATAR_PATH)
            }
            prefs[Keys.PUBLIC_KEY] = material.publicKeyBase64
            prefs[Keys.WRAPPED_PRIVATE_KEY] = material.wrappedPrivateKeyBase64
            prefs[Keys.PEER_ID] = material.peerId
            prefs[Keys.FINGERPRINT] = material.fingerprint
            prefs[Keys.LISTEN_PORT] = LocalIdentity.DEFAULT_TCP_PORT
            prefs[Keys.IS_DISCOVERABLE] = isDiscoverable
            prefs[Keys.BLOCK_UNKNOWN_PEERS] = false
            prefs[Keys.QR_INVITE_TOKEN] = inviteToken
            if ((prefs[Keys.INSTANCE_ID] ?: "").isBlank()) {
                prefs[Keys.INSTANCE_ID] = generateInstanceId()
            }
            if (prefs[Keys.BACKGROUND_CONN_ENABLED] == null) {
                prefs[Keys.BACKGROUND_CONN_ENABLED] = true
            }
        }
        return getSnapshot()
    }

    suspend fun updateProfile(username: String, avatarPath: String?) {
        store.edit { prefs ->
            prefs[Keys.USERNAME] = username.trim()
            if (avatarPath != null) {
                prefs[Keys.AVATAR_PATH] = avatarPath
            } else {
                prefs.remove(Keys.AVATAR_PATH)
            }
        }
    }

    suspend fun setDiscoverable(discoverable: Boolean) {
        store.edit { prefs ->
            prefs[Keys.IS_DISCOVERABLE] = discoverable
        }
    }

    suspend fun setBlockUnknownPeers(blockUnknown: Boolean) {
        store.edit { prefs ->
            prefs[Keys.BLOCK_UNKNOWN_PEERS] = blockUnknown
            if ((prefs[Keys.QR_INVITE_TOKEN] ?: "").isEmpty()) {
                prefs[Keys.QR_INVITE_TOKEN] = generateInviteToken()
            }
        }
    }

    suspend fun rotateQrInviteToken(): String {
        val token = generateInviteToken()
        store.edit { prefs ->
            prefs[Keys.QR_INVITE_TOKEN] = token
        }
        return token
    }

    suspend fun setListenPort(port: Int) {
        val validPort = port.coerceIn(1024, 65535)
        store.edit { prefs ->
            prefs[Keys.LISTEN_PORT] = validPort
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setThemePalette(palette: ThemePalette) {
        store.edit { prefs ->
            prefs[Keys.THEME_PALETTE] = palette.name
        }
    }

    suspend fun setThemeAdvanced(enabled: Boolean) {
        store.edit { prefs ->
            prefs[Keys.THEME_ADVANCED] = enabled
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        LocaleHelper.saveLanguage(context, language)
        store.edit { prefs ->
            prefs[Keys.LANGUAGE] = language.code
        }
    }

    suspend fun setBackgroundConnectionEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[Keys.BACKGROUND_CONN_ENABLED] = enabled
        }
    }

    suspend fun setBatteryPromptDismissed(dismissed: Boolean) {
        store.edit { prefs ->
            prefs[Keys.BATTERY_PROMPT_DISMISSED] = dismissed
        }
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[Keys.DEV_MODE_ENABLED] = enabled
        }
    }

    suspend fun setBridgeEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[Keys.BRIDGE_ENABLED] = enabled
        }
    }

    private fun generateInviteToken(): String {
        return Base64.encodeToString(
            CryptoEngine.randomBytes(12),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun generateInstanceId(): String {
        return Base64.encodeToString(
            CryptoEngine.randomBytes(16),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
