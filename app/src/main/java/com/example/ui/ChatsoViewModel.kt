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
import com.example.data.BridgeTelemetry
import com.example.data.DiscoveredPeer
import com.example.data.FavoriteStickerEntity
import com.example.data.IrohRelayMode
import com.example.data.IrohRelayStatus
import com.example.data.LocalIdentity
import com.example.data.LocalSavedMediaItem
import com.example.data.LocaleHelper
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.NetworkEndpointInfo
import com.example.data.PackStickerEntity
import com.example.data.PeerEntity
import com.example.data.SavedMediaKind
import com.example.data.StickerPackArchiveHelper
import com.example.data.StickerPackEntity
import com.example.data.StickerPackProbe
import com.example.data.ThemeMode
import com.example.data.ThemePalette
import com.example.data.UnreadDividerInfo
import com.example.media.VoicePlaybackState
import com.example.media.VoicePlayerController
import com.example.media.VoiceRecorderController
import com.example.media.VoiceRecordingState
import com.example.network.ChatsoConnectionService
import com.example.network.ChatsoSharingShortcuts
import com.example.network.IrohRelayUrlUtils
import com.example.network.NetworkUtils
import com.example.network.QrCodeHelper
import com.example.network.RawShareRequest
import com.example.network.UpdateCheckResult
import com.example.network.UpdateChecker
import com.example.ui.theme.AppIconManager
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

data class StagedSharedFile(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val messageType: MessageType,
    val detectedStickerPack: StickerPackProbe? = null
)

data class PendingIncomingShare(
    val id: String = UUID.randomUUID().toString(),
    val directTargetPeerId: String?,
    val sharedText: String?,
    val sharedFiles: List<StagedSharedFile> = emptyList(),
    val isLoadingMedia: Boolean = false,
    val isSending: Boolean = false
)

data class PendingPackImportUiState(
    val sourceFile: File,
    val displayNameHint: String,
    val deleteSourceOnFinish: Boolean,
    val probe: StickerPackProbe,
    val isImporting: Boolean = false
)

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
    val irohStatus: StateFlow<IrohRelayStatus> = repository.irohStatus
    val typingPeers: StateFlow<Set<String>> = repository.typingPeers
    val activeTransfers: StateFlow<List<ActiveTransferInfo>> = repository.activeTransfers
    val unreadDivider: StateFlow<UnreadDividerInfo?> = repository.unreadDivider
    val bridgeTelemetry: StateFlow<BridgeTelemetry> = repository.bridgeTelemetry
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

    private val _pendingIncomingShare = MutableStateFlow<PendingIncomingShare?>(null)
    val pendingIncomingShare: StateFlow<PendingIncomingShare?> = _pendingIncomingShare.asStateFlow()
    private var incomingShareStagingJob: Job? = null

    private val _pendingPackImport = MutableStateFlow<PendingPackImportUiState?>(null)
    val pendingPackImport: StateFlow<PendingPackImportUiState?> = _pendingPackImport.asStateFlow()

    private val _nowClockMs = MutableStateFlow(System.currentTimeMillis())
    val nowClockMs: StateFlow<Long> = _nowClockMs.asStateFlow()

    val voiceRecordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val voicePlaybackState: StateFlow<VoicePlaybackState> = voicePlayer.state

    val favoriteStickers: StateFlow<List<FavoriteStickerEntity>> = repository.observeFavoriteStickers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stickerPacks: StateFlow<List<StickerPackEntity>> = repository.observeStickerPacks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val packStickers: StateFlow<List<PackStickerEntity>> = repository.observePackStickers()
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
            _endpoints.value = NetworkUtils.getActiveEndpoints(getApplication())
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

    fun handleIncomingShareRequest(request: RawShareRequest) {
        incomingShareStagingJob?.cancel()
        val prevFiles = _pendingIncomingShare.value?.sharedFiles?.map { it.file }.orEmpty()
        val shareId = UUID.randomUUID().toString()
        val hasStreams = request.streamUris.isNotEmpty()
        _pendingIncomingShare.value = PendingIncomingShare(
            id = shareId,
            directTargetPeerId = request.targetPeerId,
            sharedText = request.sharedText,
            sharedFiles = emptyList(),
            isLoadingMedia = hasStreams,
            isSending = false
        )
        incomingShareStagingJob = viewModelScope.launch {
            if (prevFiles.isNotEmpty()) {
                mediaStorage.clearSharedStagingFiles(prevFiles)
            }
            val validTargetPeerId = if (!request.targetPeerId.isNullOrBlank()) {
                val allPeers = withContext(Dispatchers.IO) { repository.allPeersFlow.first() }
                if (allPeers.any { it.peerId == request.targetPeerId }) request.targetPeerId else null
            } else {
                null
            }
            val staged = if (hasStreams) {
                withContext(Dispatchers.IO) {
                    request.streamUris.mapNotNull { uri ->
                        val copied = mediaStorage.stageSharedUriToCache(uri) ?: return@mapNotNull null
                        val mime = copied.mimeType.lowercase()
                        val msgType = when {
                            mime.startsWith("image/") -> MessageType.IMAGE
                            mime.startsWith("video/") -> MessageType.VIDEO
                            else -> MessageType.FILE
                        }
                        StagedSharedFile(
                            file = copied.localFile,
                            fileName = copied.fileName,
                            mimeType = copied.mimeType,
                            sizeBytes = copied.sizeBytes,
                            messageType = msgType,
                            detectedStickerPack = if (msgType == MessageType.FILE) {
                                StickerPackArchiveHelper.probePackArchive(
                                    file = copied.localFile,
                                    displayNameHint = copied.fileName,
                                    includePreviews = true
                                )
                            } else {
                                null
                            }
                        )
                    }
                }
            } else {
                emptyList()
            }
            val active = _pendingIncomingShare.value ?: return@launch
            if (active.id != shareId) return@launch

            if (staged.isEmpty() && active.sharedText.isNullOrBlank()) {
                _pendingIncomingShare.value = null
                _snackbarMessage.value = str(R.string.share_load_failed)
            } else {
                _pendingIncomingShare.value = active.copy(
                    directTargetPeerId = validTargetPeerId,
                    sharedFiles = staged,
                    isLoadingMedia = false
                )
            }
        }
    }

    fun sendPendingShare(peerIds: Set<String>, captionOrText: String) {
        val share = _pendingIncomingShare.value ?: return
        if (share.isLoadingMedia || share.isSending || peerIds.isEmpty()) return
        val cleanText = captionOrText.trim()
        if (share.sharedFiles.isEmpty() && cleanText.isEmpty()) return

        _pendingIncomingShare.value = share.copy(isSending = true)
        viewModelScope.launch {
            try {
                peerIds.forEach { peerId ->
                    share.sharedFiles.forEach { staged ->
                        repository.sendLocalSavedFile(
                            peerId = peerId,
                            sourceFile = staged.file,
                            messageType = staged.messageType,
                            mimeType = staged.mimeType,
                            displayName = staged.fileName
                        )
                    }
                    if (cleanText.isNotEmpty()) {
                        repository.sendText(peerId, cleanText)
                    }
                }
                if (peerIds.size == 1) {
                    _selectedTab.value = HomeTab.CHATS
                    openConversation(peerIds.first())
                }
                _snackbarMessage.value = str(R.string.share_sent_success)
            } finally {
                mediaStorage.clearSharedStagingFiles(share.sharedFiles.map { it.file })
                _pendingIncomingShare.value = null
            }
        }
    }

    fun dismissPendingShare() {
        incomingShareStagingJob?.cancel()
        incomingShareStagingJob = null
        val current = _pendingIncomingShare.value
        _pendingIncomingShare.value = null
        if (current != null && current.sharedFiles.isNotEmpty()) {
            viewModelScope.launch {
                mediaStorage.clearSharedStagingFiles(current.sharedFiles.map { it.file })
            }
        }
    }

    fun importSharedStickerPack(stagedFile: StagedSharedFile, customName: String) {
        val share = _pendingIncomingShare.value ?: return
        if (share.isSending || share.isLoadingMedia) return
        _pendingIncomingShare.value = share.copy(isSending = true)
        viewModelScope.launch {
            val imported = repository.importStickerPackArchive(
                sourceFile = stagedFile.file,
                customName = customName,
                displayNameHint = stagedFile.fileName
            )
            if (imported != null) {
                mediaStorage.clearSharedStagingFiles(share.sharedFiles.map { it.file })
                _pendingIncomingShare.value = null
                _snackbarMessage.value = str(
                    R.string.sticker_pack_imported_msg,
                    imported.pack.name,
                    imported.stickers.size
                )
            } else {
                _pendingIncomingShare.value = share.copy(isSending = false)
                _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
            }
        }
    }

    fun refreshSharingShortcuts() {
        viewModelScope.launch(Dispatchers.IO) {
            val peers = repository.allPeersFlow.first()
            ChatsoSharingShortcuts.syncContactShortcuts(getApplication(), peers)
        }
    }

    private var lastTypingPeerId: String? = null
    private var lastTypingState: Boolean = false

    fun openConversation(peerId: String) {
        _messageLimit.value = 150
        _activePeerId.value = peerId
        repository.currentlyOpenPeerId = peerId
        repository.isOpenChatAtBottom = true
        viewModelScope.launch {
            repository.prepareConversationOpen(peerId, preserveExistingAnchor = false)
            repository.ensurePeerConnected(peerId)
        }
    }

    fun setActiveChatAtBottom(atBottom: Boolean) {
        val peerId = _activePeerId.value ?: return
        repository.setOpenChatAtBottom(peerId, atBottom)
    }

    fun markVisibleMessagesRead(messageIds: Set<String>) {
        val peerId = _activePeerId.value ?: return
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            repository.markVisibleMessagesRead(peerId, messageIds)
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
        repository.isOpenChatAtBottom = true
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

    fun toggleBridgeMode(enabled: Boolean) {
        viewModelScope.launch {
            identityStore.setBridgeEnabled(enabled)
            _snackbarMessage.value = str(
                if (enabled) R.string.msg_bridge_on else R.string.msg_bridge_off
            )
        }
    }

    fun setIrohRelayMode(mode: IrohRelayMode) {
        viewModelScope.launch {
            identityStore.setIrohRelayMode(mode)
            val modeLabel = when (mode) {
                IrohRelayMode.PUBLIC -> str(R.string.iroh_mode_public)
                IrohRelayMode.PRIVATE -> str(R.string.iroh_mode_private)
                IrohRelayMode.DISABLED -> str(R.string.iroh_mode_off)
            }
            _snackbarMessage.value = str(R.string.msg_iroh_mode_updated, modeLabel)
        }
    }

    fun setIrohPublicRelayUrl(url: String) {
        viewModelScope.launch {
            val clean = if (url.isBlank()) "" else (IrohRelayUrlUtils.normalizeSingleRelayUrl(url) ?: "")
            identityStore.setIrohPublicRelayUrl(clean)
        }
    }

    fun setIrohPrivateRelayUrl(rawUrl: String): Boolean {
        val parsed = IrohRelayUrlUtils.parseRelayUrlList(rawUrl)
        if (parsed == null) {
            _snackbarMessage.value = str(R.string.iroh_private_url_invalid)
            return false
        }
        val normalized = parsed.joinToString(", ")
        viewModelScope.launch {
            identityStore.setIrohPrivateRelayUrl(normalized)
            if (identityStore.getSnapshot().irohRelayMode != IrohRelayMode.PRIVATE) {
                identityStore.setIrohRelayMode(IrohRelayMode.PRIVATE)
            }
            _snackbarMessage.value = str(R.string.msg_iroh_private_saved)
        }
        return true
    }

    fun reconnectIrohRelay() {
        viewModelScope.launch {
            repository.reconnectIrohRelay()
        }
    }

    fun parseIrohTicket(raw: String): Pair<String, String>? = repository.parseIrohTicket(raw)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            identityStore.setThemeMode(mode)
        }
    }

    fun setThemePalette(palette: ThemePalette) {
        val advanced = identityState.value?.themeAdvanced ?: false
        com.example.network.ChatsoNotificationHelper.syncThemeConfig(getApplication(), palette, advanced)
        viewModelScope.launch {
            identityStore.setThemePalette(palette)
            AppIconManager.scheduleIconSync(
                palette = palette,
                fullThemeEnabled = advanced
            )
        }
    }

    fun setThemeAdvanced(enabled: Boolean) {
        val palette = identityState.value?.themePalette ?: ThemePalette.PURPLE
        com.example.network.ChatsoNotificationHelper.syncThemeConfig(getApplication(), palette, enabled)
        viewModelScope.launch {
            identityStore.setThemeAdvanced(enabled)
            AppIconManager.scheduleIconSync(
                palette = palette,
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

    private val _isRunningBridgeTest = MutableStateFlow(false)
    val isRunningBridgeTest: StateFlow<Boolean> = _isRunningBridgeTest.asStateFlow()

    fun devCreateFakePeer(username: String, onCreated: (PeerEntity) -> Unit = {}) {
        viewModelScope.launch {
            val peer = repository.devCreateFakePeer(username)
            _snackbarMessage.value = str(R.string.dev_msg_fake_created, peer.username)
            onCreated(peer)
        }
    }

    fun devCreateBridgedFakePeer(username: String = "", onCreated: (PeerEntity) -> Unit = {}) {
        viewModelScope.launch {
            val peer = repository.devCreateBridgedFakePeer(username)
            _snackbarMessage.value = str(R.string.dev_msg_fake_created, peer.username)
            onCreated(peer)
        }
    }

    fun devMarkOutgoingMessagesRead(peerId: String) {
        viewModelScope.launch {
            val count = repository.devMarkOutgoingMessagesRead(peerId)
            _snackbarMessage.value = if (count > 0) {
                str(R.string.dev_msg_read_receipts_sent, count)
            } else {
                str(R.string.dev_msg_no_outgoing_for_receipt)
            }
        }
    }

    fun devRunLoopbackBridgeTest() {
        if (_isRunningBridgeTest.value) return
        viewModelScope.launch {
            _isRunningBridgeTest.value = true
            try {
                val res = repository.devRunLoopbackBridgeTest()
                _snackbarMessage.value = res.fold(
                    onSuccess = { str(R.string.dev_msg_bridge_test_ok) },
                    onFailure = { err ->
                        str(R.string.dev_msg_bridge_test_fail, err.message ?: "Error")
                    }
                )
            } finally {
                _isRunningBridgeTest.value = false
            }
        }
    }

    fun devResetBridgeTelemetry() {
        repository.devResetBridgeTelemetry()
        _snackbarMessage.value = str(R.string.dev_msg_bridge_stats_reset)
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
        val bridgeId = peer.viaBridgePeerId?.takeIf { it.isNotBlank() }
        if (bridgeId != null) {
            connectViaBridgeInternal(peer, bridgeId)
        } else {
            connectInternal(
                ip = peer.ipAddress,
                port = peer.port,
                expectedPub = peer.publicKeyBase64,
                inviteToken = "",
                expectedInstanceId = peer.instanceId
            )
        }
    }

    private fun connectViaBridgeInternal(peer: DiscoveredPeer, bridgePeerId: String) {
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            val result = try {
                val alreadyConnectedToBridge =
                    repository.nodeManager.connectedPeers.value.contains(bridgePeerId)
                if (!alreadyConnectedToBridge && !peer.bridgeHost.isNullOrBlank()) {
                    repository.connectDirect(peer.bridgeHost, peer.bridgePort)
                }
                repository.connectViaBridge(
                    bridgePeerId = bridgePeerId,
                    targetPeerId = peer.peerId,
                    expectedPubKeyBase64 = peer.publicKeyBase64,
                    expectedInstanceId = peer.instanceId
                )
            } finally {
                _isConnecting.value = false
            }
            result.fold(
                onSuccess = { peerId ->
                    _selectedTab.value = HomeTab.CHATS
                    openConversation(peerId)
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: peer.username
                }
            )
        }
    }

    fun connectFromQrString(rawQr: String) {
        val parsed = QrCodeHelper.parseInviteUri(rawQr, ::parseIrohTicket)
        if (parsed == null) {
            _snackbarMessage.value = str(R.string.msg_invalid_qr)
            return
        }
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            try {
                val hasTcp = parsed.ip.isNotBlank() && parsed.port in 1..65535
                var result: Result<String>? = if (hasTcp) {
                    repository.connectDirect(
                        ip = parsed.ip,
                        port = parsed.port,
                        expectedPubKeyBase64 = parsed.publicKeyBase64,
                        inviteToken = parsed.inviteToken,
                        expectedInstanceId = parsed.instanceId
                    )
                } else {
                    null
                }
                if ((result == null || result.isFailure) && parsed.irohNodeId.isNotBlank()) {
                    result = repository.connectViaIroh(
                        targetEndpointId = parsed.irohNodeId,
                        targetRelayUrl = parsed.irohRelayUrl,
                        expectedPubKeyBase64 = parsed.publicKeyBase64,
                        inviteToken = parsed.inviteToken,
                        expectedInstanceId = parsed.instanceId
                    )
                }
                val finalResult = result
                    ?: Result.failure(IllegalStateException(str(R.string.msg_invalid_qr)))
                finalResult.fold(
                    onSuccess = { peerId ->
                        _selectedTab.value = HomeTab.CHATS
                        openConversation(peerId)
                    },
                    onFailure = { err ->
                        _snackbarMessage.value = err.message
                            ?: parsed.ip.ifBlank { IrohRelayUrlUtils.shortEndpointId(parsed.irohNodeId) }
                    }
                )
            } finally {
                _isConnecting.value = false
            }
        }
    }

    fun connectByIroh(inputText: String, relayUrlInput: String = "") {
        val parsed = QrCodeHelper.parseIrohConnectInput(
            rawText = inputText,
            explicitRelayUrl = relayUrlInput,
            ticketParser = ::parseIrohTicket
        )
        if (parsed == null || parsed.irohNodeId.isBlank()) {
            _snackbarMessage.value = str(R.string.iroh_invalid_input)
            return
        }
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            try {
                val result = repository.connectViaIroh(
                    targetEndpointId = parsed.irohNodeId,
                    targetRelayUrl = parsed.irohRelayUrl,
                    expectedPubKeyBase64 = parsed.publicKeyBase64,
                    inviteToken = parsed.inviteToken,
                    expectedInstanceId = parsed.instanceId
                )
                result.fold(
                    onSuccess = { peerId ->
                        _selectedTab.value = HomeTab.CHATS
                        openConversation(peerId)
                    },
                    onFailure = { err ->
                        _snackbarMessage.value = err.message
                            ?: IrohRelayUrlUtils.shortEndpointId(parsed.irohNodeId)
                    }
                )
            } finally {
                _isConnecting.value = false
            }
        }
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
        inviteToken: String,
        expectedInstanceId: String = ""
    ) {
        if (_isConnecting.value) return
        viewModelScope.launch {
            _isConnecting.value = true
            val result = repository.connectDirect(
                ip = ip,
                port = port,
                expectedPubKeyBase64 = expectedPub,
                inviteToken = inviteToken,
                expectedInstanceId = expectedInstanceId
            )
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
                _snackbarMessage.value = str(R.string.msg_message_queued)
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
        sendSavedStickerFile(sticker.localPath, sticker.mimeType, replyTo)
    }

    fun sendSavedStickerFile(
        localPath: String,
        mimeType: String,
        replyTo: MessageEntity? = null
    ) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendSavedStickerFile(peerId, localPath, mimeType, replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_message_queued)
            }
        }
    }

    fun toggleFavoriteStickerFile(localPath: String, mimeType: String) {
        viewModelScope.launch {
            when (repository.toggleFavoriteStickerFile(localPath, mimeType)) {
                true -> _snackbarMessage.value = str(R.string.msg_sticker_favorited)
                false -> _snackbarMessage.value = str(R.string.msg_sticker_unfavorited)
                null -> Unit
            }
        }
    }

    fun removePackSticker(stickerId: String) {
        viewModelScope.launch {
            repository.removePackSticker(stickerId)
        }
    }

    fun renameStickerPack(packId: String, newName: String) {
        viewModelScope.launch {
            repository.renameStickerPack(packId, newName)
        }
    }

    fun deleteStickerPack(packId: String) {
        viewModelScope.launch {
            repository.deleteStickerPack(packId)
            _snackbarMessage.value = str(R.string.sticker_pack_deleted_msg)
        }
    }

    fun requestImportStickerPackFromFile(filePath: String, displayName: String?) {
        val file = File(filePath)
        if (!file.exists()) {
            _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
            return
        }
        viewModelScope.launch {
            val probe = StickerPackArchiveHelper.probePackArchive(
                file = file,
                displayNameHint = displayName,
                includePreviews = true
            )
            if (probe == null) {
                _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
            } else {
                _pendingPackImport.value = PendingPackImportUiState(
                    sourceFile = file,
                    displayNameHint = displayName ?: file.name,
                    deleteSourceOnFinish = false,
                    probe = probe
                )
            }
        }
    }

    fun requestImportStickerPackFromUri(uri: Uri) {
        viewModelScope.launch {
            val staged = mediaStorage.stageSharedUriToCache(uri)
            if (staged == null) {
                _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
                return@launch
            }
            val probe = StickerPackArchiveHelper.probePackArchive(
                file = staged.localFile,
                displayNameHint = staged.fileName,
                includePreviews = true
            )
            if (probe == null) {
                mediaStorage.clearSharedStagingFiles(listOf(staged.localFile))
                _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
            } else {
                _pendingPackImport.value = PendingPackImportUiState(
                    sourceFile = staged.localFile,
                    displayNameHint = staged.fileName,
                    deleteSourceOnFinish = true,
                    probe = probe
                )
            }
        }
    }

    fun confirmPendingPackImport(customName: String) {
        val pending = _pendingPackImport.value ?: return
        if (pending.isImporting) return
        _pendingPackImport.value = pending.copy(isImporting = true)
        viewModelScope.launch {
            try {
                val imported = repository.importStickerPackArchive(
                    sourceFile = pending.sourceFile,
                    customName = customName,
                    displayNameHint = pending.displayNameHint
                )
                if (imported != null) {
                    _snackbarMessage.value = str(
                        R.string.sticker_pack_imported_msg,
                        imported.pack.name,
                        imported.stickers.size
                    )
                    _pendingPackImport.value = null
                } else {
                    _pendingPackImport.value = pending.copy(isImporting = false)
                    _snackbarMessage.value = str(R.string.sticker_pack_import_invalid)
                }
            } finally {
                if (pending.deleteSourceOnFinish) {
                    mediaStorage.clearSharedStagingFiles(listOf(pending.sourceFile))
                }
            }
        }
    }

    fun dismissPendingPackImport() {
        val current = _pendingPackImport.value ?: return
        _pendingPackImport.value = null
        if (current.deleteSourceOnFinish) {
            viewModelScope.launch {
                mediaStorage.clearSharedStagingFiles(listOf(current.sourceFile))
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
                _snackbarMessage.value = str(R.string.msg_message_queued)
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
                _snackbarMessage.value = str(R.string.msg_message_queued)
            }
        }
    }

    fun sendVideoUri(uri: Uri, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendUriAttachment(peerId, uri, forceImage = false, replyTo = replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_message_queued)
            }
        }
    }

    fun sendDocumentUri(uri: Uri, replyTo: MessageEntity? = null) {
        val peerId = _activePeerId.value ?: return
        viewModelScope.launch {
            val ok = repository.sendUriAttachment(peerId, uri, forceImage = false, replyTo = replyTo)
            if (!ok) {
                _snackbarMessage.value = str(R.string.msg_message_queued)
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
                _snackbarMessage.value = str(R.string.msg_message_queued)
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
                _snackbarMessage.value = str(R.string.msg_message_queued)
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
