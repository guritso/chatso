package com.example.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ChatsoApplication
import com.example.R
import com.example.crypto.CryptoEngine
import com.example.data.ActiveTransferInfo
import com.example.data.AppLanguage
import com.example.data.DiscoveredPeer
import com.example.data.FavoriteStickerEntity
import com.example.data.LocalIdentity
import com.example.data.LocalSavedMediaItem
import com.example.data.LocaleHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.NetworkEndpointInfo
import com.example.data.PeerEntity
import com.example.data.SavedMediaKind
import com.example.data.ThemeMode
import com.example.data.ThemePalette
import com.example.data.UnreadDividerInfo
import com.example.media.VoicePlaybackState
import com.example.media.VoicePlayerController
import com.example.media.VoiceRecorderController
import com.example.media.VoiceRecordingState
import com.example.network.ChatsoConnectionService
import com.example.network.NetworkUtils
import com.example.network.QrCodeHelper
import com.example.network.UpdateCheckResult
import com.example.network.UpdateChecker
import com.example.ui.theme.AppIconManager
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeTab {
    CHATS,
    CONNECT,
    PROFILE,
    SETTINGS
}

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class UpdateAvailable(val info: UpdateCheckResult.UpdateAvailable) : UpdateUiState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsoViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ChatsoApplication).container
    private val identityStore = container.identityStore
    private val mediaStorage = container.mediaStorage
    val repository = container.repository

    private val voiceRecorder = VoiceRecorderController(application, viewModelScope)
    private val voicePlayer = VoicePlayerController(viewModelScope)

    val identityState: StateFlow<LocalIdentity?> = identityStore.identityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val peersState: StateFlow<List<PeerEntity>> = repository.allPeersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedPeerIds: StateFlow<Set<String>> = combine(
        repository.nodeManager.connectedPeers,
        repository.devOnlineFakePeers
    ) { real, dev -> real + dev }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val foregroundPeerIds: StateFlow<Set<String>> = combine(
        repository.nodeManager.foregroundPeers,
        repository.devOnlineFakePeers
    ) { real, dev -> real + dev }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val boundPort: StateFlow<Int> = repository.nodeManager.boundPort
    val serverRunning: StateFlow<Boolean> = repository.nodeManager.serverRunning
    val typingPeers: StateFlow<Set<String>> = repository.typingPeers
    val activeTransfers: StateFlow<List<ActiveTransferInfo>> = repository.activeTransfers
    val unreadDivider: StateFlow<UnreadDividerInfo?> = repository.unreadDivider
    val devOnlineFakePeers: StateFlow<Set<String>> = repository.devOnlineFakePeers
    val devAutoReplyEnabled: StateFlow<Boolean> = repository.devAutoReplyEnabled
    val totalMessageCount: StateFlow<Int> = repository.totalMessageCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _scheduledFakeDelayRemainingSec = MutableStateFlow<Int?>(null)
    val scheduledFakeDelayRemainingSec: StateFlow<Int?> = _scheduledFakeDelayRemainingSec.asStateFlow()
    private var scheduledFakeJob: Job? = null

    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = repository.discoveryManager.discoveredPeers
        .map { it.values.sortedByDescending { p -> p.lastSeenAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanningNetwork: StateFlow<Boolean> = repository.discoveryManager.isScanning

    private val _selectedTab = MutableStateFlow(HomeTab.CHATS)
    val selectedTab: StateFlow<HomeTab> = _selectedTab.asStateFlow()

    private val _activePeerId = MutableStateFlow<String?>(null)
    val activePeerId: StateFlow<String?> = _activePeerId.asStateFlow()

    private val _messageLimit = MutableStateFlow(150)
    val currentMessageLimit: StateFlow<Int> = _messageLimit.asStateFlow()

    val activePeer: StateFlow<PeerEntity?> = _activePeerId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observePeer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeMessages: StateFlow<List<MessageEntity>> = combine(_activePeerId, _messageLimit) { id, limit ->
        id to limit
    }.flatMapLatest { (id, limit) ->
        if (id == null) flowOf(emptyList()) else repository.observeMessages(id, limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _endpoints = MutableStateFlow<List<NetworkEndpointInfo>>(emptyList())
    val endpoints: StateFlow<List<NetworkEndpointInfo>> = _endpoints.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _updateUiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateUiState: StateFlow<UpdateUiState> = _updateUiState.asStateFlow()

    private val _nowClockMs = MutableStateFlow(System.currentTimeMillis())
    val nowClockMs: StateFlow<Long> = _nowClockMs.asStateFlow()

    val voiceRecordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val voicePlaybackState: StateFlow<VoicePlaybackState> = voicePlayer.state

    val favoriteStickers: StateFlow<List<FavoriteStickerEntity>> = repository.observeFavoriteStickers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val onboardingPreviewMaterial: CryptoEngine.IdentityKeyMaterial by lazy {
        CryptoEngine.createIdentityMaterial()
    }

    init {
        refreshEndpoints()
        viewModelScope.launch {
            repository.transferAlertResIds.collect { resId ->
                _snackbarMessage.value = str(resId)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _nowClockMs.value = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    private fun str(resId: Int, vararg args: Any): String {
        val localizedCtx = LocaleHelper.wrapContext(getApplication())
        return if (args.isEmpty()) localizedCtx.getString(resId) else localizedCtx.getString(resId, *args)
    }

    fun refreshEndpoints() {
        viewModelScope.launch(Dispatchers.IO) {
            _endpoints.value = NetworkUtils.getActiveEndpoints()
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun postMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun selectTab(tab: HomeTab) {
        _selectedTab.value = tab
        if (tab == HomeTab.CONNECT || tab == HomeTab.SETTINGS) {
            refreshEndpoints()
        }
    }

    private var lastTypingPeerId: String? = null
    private var lastTypingState: Boolean = false

    fun openConversation(peerId: String) {
        _messageLimit.value = 150
        _activePeerId.value = peerId
        repository.currentlyOpenPeerId = peerId
        viewModelScope.launch {
            repository.captureUnreadDividerAndMarkRead(peerId, preserveExistingAnchor = false)
            repository.ensurePeerConnected(peerId)
        }
    }

    fun loadMoreMessages() {
        val currentSize = activeMessages.value.size
        if (currentSize >= _messageLimit.value) {
            _messageLimit.value += 150
        }
    }

    fun closeConversation() {
        val prev = _activePeerId.value
        _activePeerId.value = null
        repository.currentlyOpenPeerId = null
        repository.clearUnreadDivider()
        lastTypingPeerId = null
        lastTypingState = false
        voiceRecorder.stopAndDiscard()
        voicePlayer.stop()
        if (prev != null) {
            viewModelScope.launch {
                repository.nodeManager.sendTyping(prev, false)
            }
        }
    }

    fun completeOnboarding(username: String, avatarUri: Uri?, isDiscoverable: Boolean) {
        if (username.isBlank()) {
            _snackbarMessage.value = str(R.string.msg_enter_username)
            return
        }
        viewModelScope.launch {
            val savedAvatar = avatarUri?.let { mediaStorage.saveProfileAvatar(it) }
            identityStore.createIdentity(
                username = username,
                avatarPath = savedAvatar,
                isDiscoverable = isDiscoverable,
                pregeneratedMaterial = onboardingPreviewMaterial
            )
            refreshEndpoints()
        }
    }

    fun updateProfile(username: String, newAvatarUri: Uri?, keepExistingAvatar: Boolean) {
        if (username.isBlank()) {
            _snackbarMessage.value = str(R.string.msg_enter_username)
            return
        }
        viewModelScope.launch {
            val current = identityStore.getSnapshot()
            val avatarPath = when {
                newAvatarUri != null -> mediaStorage.saveProfileAvatar(newAvatarUri)
                keepExistingAvatar -> current.avatarPath
                else -> null
            }
            identityStore.updateProfile(username, avatarPath)
            _snackbarMessage.value = str(R.string.msg_profile_saved)
        }
    }

    fun toggleDiscoverable(discoverable: Boolean) {
        viewModelScope.launch {
            identityStore.setDiscoverable(discoverable)
            _snackbarMessage.value = if (discoverable) {
                str(R.string.msg_visible_on)
            } else {
                str(R.string.msg_invisible_on)
            }
        }
    }

    fun toggleBlockUnknownPeers(block: Boolean) {
        viewModelScope.launch {
            identityStore.setBlockUnknownPeers(block)
            _snackbarMessage.value = if (block) {
                str(R.string.msg_block_unknown_on)
            } else {
                str(R.string.msg_block_unknown_off)
            }
        }
    }

    fun rotateQrToken() {
        viewModelScope.launch {
            identityStore.rotateQrInviteToken()
            _snackbarMessage.value = str(R.string.msg_qr_renewed)
        }
    }

    fun updateListenPort(portText: String) {
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            _snackbarMessage.value = str(R.string.msg_invalid_port)
            return
        }
        viewModelScope.launch {
            identityStore.setListenPort(port)
            _snackbarMessage.value = str(R.string.msg_port_applied, port)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            identityStore.setThemeMode(mode)
        }
    }

    fun setThemePalette(palette: ThemePalette) {
        viewModelScope.launch {
            identityStore.setThemePalette(palette)
            AppIconManager.scheduleIconSync(
                palette = palette,
                fullThemeEnabled = identityState.value?.themeAdvanced ?: false
            )
        }
    }

    fun setThemeAdvanced(enabled: Boolean) {
        viewModelScope.launch {
            identityStore.setThemeAdvanced(enabled)
            AppIconManager.scheduleIconSync(
                palette = identityState.value?.themePalette ?: ThemePalette.PURPLE,
                fullThemeEnabled = enabled
            )
        }
    }

    fun setLanguage(language: AppLanguage, onApplied: () -> Unit) {
        LocaleHelper.saveLanguage(getApplication(), language)
        viewModelScope.launch {
            identityStore.setLanguage(language)
            onApplied()
        }
    }

    fun toggleBackgroundConnection(enabled: Boolean) {
        viewModelScope.launch {
            identityStore.setBackgroundConnectionEnabled(enabled)
            ChatsoConnectionService.syncServiceState(getApplication(), enabled)
            _snackbarMessage.value = if (enabled) {
                str(R.string.msg_bg_conn_on)
            } else {
                str(R.string.msg_bg_conn_off)
            }
        }
    }

    fun dismissBatteryPrompt() {
        viewModelScope.launch {
            identityStore.setBatteryPromptDismissed(true)
        }
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            identityStore.setDevModeEnabled(enabled)
        }
    }

    fun devCreateFakePeer(username: String, onCreated: (PeerEntity) -> Unit = {}) {
        viewModelScope.launch {
            val peer = repository.devCreateFakePeer(username)
            _snackbarMessage.value = str(R.string.dev_msg_fake_created, peer.username)
            onCreated(peer)
        }
    }

    fun devSendFakeIncomingMessage(peerId: String, text: String, delaySeconds: Int = 0) {
        if (delaySeconds <= 0) {
            container.appScope.launch {
                if (repository.devSimulateIncomingText(peerId, text)) {
                    _snackbarMessage.value = str(R.string.dev_msg_fake_delivered)
                }
            }
            return
        }
        scheduledFakeJob?.cancel()
        // Launch on container.appScope so countdown completes even when MainActivity is in background
        scheduledFakeJob = container.appScope.launch {
            try {
                for (rem in delaySeconds downTo 1) {
                    _scheduledFakeDelayRemainingSec.value = rem
                    delay(1000L)
                }
                _scheduledFakeDelayRemainingSec.value = null
                repository.devSimulateIncomingText(peerId, text)
            } finally {
                _scheduledFakeDelayRemainingSec.value = null
            }
        }
    }

    fun devSendFakeUnreadBurst(peerId: String, baseText: String, count: Int = 5) {
        container.appScope.launch {
            val baseTime = System.currentTimeMillis() - (count * 600L)
            val prefix = baseText.trim().ifBlank { "Test message" }
            for (i in 1..count) {
                repository.devSimulateIncomingText(
                    peerId = peerId,
                    rawText = "$prefix #$i",
                    createdAt = baseTime + (i * 600L)
                )
            }
            _snackbarMessage.value = str(R.string.dev_msg_fake_delivered)
        }
    }

    fun devSendFakeIncomingMedia(peerId: String, asSticker: Boolean) {
        container.appScope.launch {
            if (repository.devSimulateIncomingMedia(peerId, asSticker)) {
                _snackbarMessage.value = str(R.string.dev_msg_fake_delivered)
            }
        }
    }

    fun devSimulateTyping(peerId: String) {
        viewModelScope.launch {
            repository.devSimulateTyping(peerId, 4000L)
        }
    }

    fun devSimulateReaction(peerId: String, emoji: String = "❤️") {
        viewModelScope.launch {
            val ok = repository.devSimulateIncomingReaction(peerId, emoji)
            if (!ok) {
                _snackbarMessage.value = str(R.string.dev_msg_no_message_for_reaction)
            }
        }
    }

    fun devTriggerKeyChangeWarning(peer: PeerEntity) {
        viewModelScope.launch {
            repository.devTriggerKeyChangeWarning(peer.peerId)
            _snackbarMessage.value = str(R.string.dev_msg_key_warning, peer.username)
        }
    }

    fun devSetPeerSimulatedOnline(peerId: String, online: Boolean) {
        repository.devSetPeerSimulatedOnline(peerId, online)
    }

    fun devSetAutoReplyEnabled(enabled: Boolean) {
        repository.devSetAutoReplyEnabled(enabled)
    }

    fun devClearFakePeers() {
        viewModelScope.launch {
            val active = _activePeerId.value
            if (active != null && active.startsWith(com.example.data.ChatRepository.DEV_FAKE_PEER_PREFIX)) {
                closeConversation()
            }
            repository.devClearFakePeers()
            _snackbarMessage.value = str(R.string.dev_msg_fakes_cleared)
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        container.isAppInForeground = inForeground
        repository.onAppForegroundChanged(inForeground)
    }

    fun checkForUpdates(manual: Boolean) {
        if (_updateUiState.value is UpdateUiState.Checking) return
        viewModelScope.launch {
            _updateUiState.value = UpdateUiState.Checking
            when (val res = UpdateChecker.checkLatestRelease()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _updateUiState.value = UpdateUiState.UpdateAvailable(res)
                }
                is UpdateCheckResult.UpToDate -> {
                    _updateUiState.value = UpdateUiState.Idle
                    if (manual) {
                        _snackbarMessage.value = str(R.string.update_up_to_date)
                    }
                }
                is UpdateCheckResult.Error -> {
                    _updateUiState.value = UpdateUiState.Idle
                    if (manual) {
                        _snackbarMessage.value = str(R.string.update_check_failed)
                    }
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateUiState.value = UpdateUiState.Idle
    }

    fun scanLanAndVpn() {
        refreshEndpoints()
        viewModelScope.launch {
            repository.scanKnownAndNetworkPeers()
        }
    }

    fun connectByManualIp(ip: String, portText: String) {
        val cleanIp = ip.trim()
        val port = portText.trim().toIntOrNull() ?: LocalIdentity.DEFAULT_TCP_PORT
        if (cleanIp.isEmpty()) {
            _snackbarMessage.value = str(R.string.msg_enter_ip)
            return
        }
        connectInternal(cleanIp, port, "", "")
    }

    fun connectToDiscoveredPeer(peer: DiscoveredPeer) {
        connectInternal(peer.ipAddress, peer.port, peer.publicKeyBase64, "")
    }

    fun connectFromQrString(rawQr: String) {
        val parsed = QrCodeHelper.parseInviteUri(rawQr)
        if (parsed == null) {
            _snackbarMessage.value = str(R.string.msg_invalid_qr)
            return
        }
        connectInternal(parsed.ip, parsed.port, parsed.publicKeyBase64, parsed.inviteToken)
    }

    fun decodeQrFromGalleryUri(uri: Uri) {
        viewModelScope.launch {
            val decodedText = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream) ?: return@withContext null
                        QrCodeHelper.decodeFromBitmap(bmp)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (decodedText == null) {
                _snackbarMessage.value = str(R.string.msg_no_qr_image)
            } else {
                connectFromQrString(decodedText)
            }
        }
    }

    private fun connectInternal(
        ip: String,
        port: Int,
        expectedPub: String,
        inviteToken: String
    ) {
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            val result = repository.connectDirect(ip, port, expectedPub, inviteToken)
            _isConnecting.value = false
            result.fold(
                onSuccess = { peerId ->
                    _selectedTab.value = HomeTab.CHATS
                    openConversation(peerId)
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: "$ip:$port"
                }
            )
        }
    }

    fun sendTextMessage(text: String, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        lastTypingPeerId = peerId
        lastTypingState = false
        viewModelScope.launch {
            repository.nodeManager.sendTyping(peerId, false)
            val ok = repository.sendText(peerId, text, replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    suspend fun isMessageStickerFavorited(message: MessageEntity): Boolean {
        return repository.isMessageStickerFavorited(message)
    }

    fun toggleFavoriteSticker(message: MessageEntity) {
        viewModelScope.launch {
            when (repository.toggleFavoriteSticker(message)) {
                true -> _snackbarMessage.value = str(R.string.msg_sticker_favorited)
                false -> _snackbarMessage.value = str(R.string.msg_sticker_unfavorited)
                null -> Unit
            }
        }
    }

    fun removeFavoriteSticker(stickerId: String) {
        viewModelScope.launch {
            repository.removeFavoriteSticker(stickerId)
            _snackbarMessage.value = str(R.string.msg_sticker_unfavorited)
        }
    }

    fun sendFavoriteSticker(sticker: FavoriteStickerEntity, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendFavoriteSticker(peerId, sticker, replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun sendLocalSavedFile(
        file: File,
        messageType: MessageType,
        mimeType: String,
        displayName: String,
        replyTo: MessageEntity? = null
    ) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendLocalSavedFile(
                peerId = peerId,
                sourceFile = file,
                messageType = messageType,
                mimeType = mimeType,
                displayName = displayName,
                replyTo = replyTo
            )
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    suspend fun listSavedMediaItems(kind: SavedMediaKind): List<LocalSavedMediaItem> {
        return mediaStorage.listSavedMediaItems(kind)
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            repository.toggleReaction(peerId, messageId, emoji)
        }
    }

    fun notifyTyping(isTyping: Boolean) {
        val peerId = _activePeerId.value ?: return
        if (lastTypingPeerId == peerId && lastTypingState == isTyping) return
        lastTypingPeerId = peerId
        lastTypingState = isTyping
        viewModelScope.launch {
            repository.nodeManager.sendTyping(peerId, isTyping)
        }
    }

    fun sendPhotoUri(uri: Uri, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendUriAttachment(peerId, uri, forceImage = true, replyTo = replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun sendVideoUri(uri: Uri, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendUriAttachment(peerId, uri, forceImage = false, replyTo = replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun sendDocumentUri(uri: Uri, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendUriAttachment(peerId, uri, forceImage = false, replyTo = replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun sendStickerStream(
        inputStream: InputStream,
        mimeType: String,
        fileNameHint: String? = null,
        replyTo: MessageEntity? = null
    ) {
        val peerId = _activePeerId.value
        if (peerId == null) {
            runCatching { inputStream.close() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.sendStickerStream(
                peerId = peerId,
                inputStream = inputStream,
                mimeType = mimeType,
                fileNameHint = fileNameHint,
                replyTo = replyTo
            )
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun cancelTransfer(messageId: String) {
        viewModelScope.launch {
            repository.cancelTransfer(messageId)
        }
    }

    fun retryDownload(messageId: String) {
        viewModelScope.launch {
            repository.retryIncomingDownload(messageId)
        }
    }

    fun startVoiceRecording() {
        val file = mediaStorage.createNewVoiceRecordingFile()
        voiceRecorder.startRecording(file)
    }

    fun cancelVoiceRecording() {
        voiceRecorder.stopAndDiscard()
    }

    fun finishAndSendVoiceRecording(replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        val finalized = voiceRecorder.stopAndFinalize() ?: return
        viewModelScope.launch {
            val ok = repository.sendVoiceRecording(peerId, finalized.first, finalized.second, replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_peer_offline)
            }
        }
    }

    fun toggleVoicePlayback(messageId: String, localPath: String) {
        voicePlayer.togglePlayPause(messageId, localPath)
    }

    fun openAttachmentExternally(localPath: String, mimeType: String?) {
        mediaStorage.openOrShareFile(localPath, mimeType)
    }

    fun setSelfDestructSeconds(seconds: Int) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            repository.setSelfDestructTimer(peerId, seconds)
        }
    }

    fun verifyActivePeerKey(verified: Boolean) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            repository.markPeerVerified(peerId, verified)
        }
    }

    fun reconnectActivePeer() {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.ensurePeerConnected(peerId)
            _snackbarMessage.value = if (ok) {
                str(R.string.msg_reconnected)
            } else {
                str(R.string.msg_reconnect_failed)
            }
        }
    }

    fun deleteSingleMessage(messageId: String) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            repository.deleteSingleMessage(peerId, messageId)
            _snackbarMessage.value = str(R.string.msg_deleted)
        }
    }

    fun deleteActiveConversation() {
        val peerId = _activePeerId.value ?: return
        closeConversation()
        viewModelScope.launch {
            repository.deleteConversation(peerId)
            _snackbarMessage.value = str(R.string.msg_chat_deleted)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.stopAndDiscard()
        voicePlayer.stop()
    }
}
