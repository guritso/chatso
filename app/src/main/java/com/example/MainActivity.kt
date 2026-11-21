package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.LocaleHelper
import com.example.data.PeerPresence
import com.example.data.ThemeMode
import com.example.data.ThemePalette
import com.example.network.ChatsoConnectionService
import com.example.network.ChatsoNotificationHelper
import com.example.network.NetworkUtils
import com.example.ui.ChatsoViewModel
import com.example.ui.HomeTab
import com.example.ui.UpdateUiState
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.ConnectScreen
import com.example.ui.screens.DevModeScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AppIconManager
import com.example.ui.theme.ChatsoTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingNotificationPeerId = MutableStateFlow<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingNotificationPeerId.value =
            intent?.getStringExtra(ChatsoNotificationHelper.EXTRA_PEER_ID)
        setContent {
            val peerIdToOpen by pendingNotificationPeerId.collectAsStateWithLifecycle()
            ChatsoRootApp(
                notificationPeerId = peerIdToOpen,
                onConsumedNotificationPeer = { pendingNotificationPeerId.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val peerId = intent.getStringExtra(ChatsoNotificationHelper.EXTRA_PEER_ID)
        if (!peerId.isNullOrBlank()) {
            pendingNotificationPeerId.value = peerId
        }
    }

    override fun onStart() {
        super.onStart()
        AppIconManager.clearTemporarySuppression()
        (application as? ChatsoApplication)?.container?.let { container ->
            container.isAppInForeground = true
            container.repository.onAppForegroundChanged(true)
        }
    }

    override fun onStop() {
        (application as? ChatsoApplication)?.container?.let { container ->
            container.isAppInForeground = false
            container.repository.onAppForegroundChanged(false)
        }
        if (!isChangingConfigurations) {
            AppIconManager.flushPendingIconIfNeeded(this)
        }
        super.onStop()
    }
}

@Composable
fun ChatsoRootApp(
    viewModel: ChatsoViewModel = viewModel(),
    notificationPeerId: String? = null,
    onConsumedNotificationPeer: () -> Unit = {}
) {
    val context = LocalContext.current
    val identity by viewModel.identityState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()

    val isDarkTheme = when (identity?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> systemDark
    }

    var askedNotifPermission by rememberSaveable { mutableStateOf(false) }
    var checkedUpdateOnLaunch by rememberSaveable { mutableStateOf(false) }
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(identity?.isInitialized, identity?.backgroundConnectionEnabled) {
        val id = identity ?: return@LaunchedEffect
        ChatsoConnectionService.syncServiceState(
            context = context,
            shouldRun = id.isInitialized && id.backgroundConnectionEnabled
        )
        if (id.isInitialized && !checkedUpdateOnLaunch) {
            checkedUpdateOnLaunch = true
            viewModel.checkForUpdates(manual = false)
        }
        if (id.isInitialized && id.backgroundConnectionEnabled &&
            !askedNotifPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            askedNotifPermission = true
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(notificationPeerId, identity?.isInitialized) {
        if (!notificationPeerId.isNullOrBlank() && identity?.isInitialized == true) {
            viewModel.selectTab(HomeTab.CHATS)
            viewModel.openConversation(notificationPeerId)
            onConsumedNotificationPeer()
        }
    }

    val activePalette = identity?.themePalette ?: ThemePalette.PURPLE
    val themeAdvanced = identity?.themeAdvanced ?: false

    LaunchedEffect(identity != null, activePalette, themeAdvanced) {
        if (identity != null) {
            AppIconManager.scheduleIconSync(activePalette, themeAdvanced)
        }
    }

    ChatsoTheme(
        darkTheme = isDarkTheme,
        palette = activePalette,
        advanced = themeAdvanced
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val snackbarMsg by viewModel.snackbarMessage.collectAsStateWithLifecycle()

        LaunchedEffect(snackbarMsg) {
            val msg = snackbarMsg ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }

        val currentIdentity = identity
        if (currentIdentity == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (!currentIdentity.isInitialized) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background
            ) { _ ->
                OnboardingScreen(
                    onCreateIdentity = { name, avatarUri, isDiscoverable ->
                        viewModel.completeOnboarding(name, avatarUri, isDiscoverable)
                    }
                )
            }
        } else {
            ChatsoAuthenticatedShell(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState
            )

            // Ask user once to disable battery optimization for reliable background P2P messaging
            val needsBatteryExemption = remember(currentIdentity.batteryPromptDismissed) {
                !currentIdentity.batteryPromptDismissed &&
                    !NetworkUtils.isIgnoringBatteryOptimizations(context)
            }
            if (needsBatteryExemption) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissBatteryPrompt() },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = { Text(stringResource(R.string.battery_dialog_title)) },
                    text = { Text(stringResource(R.string.battery_dialog_body)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.dismissBatteryPrompt()
                                NetworkUtils.openBatteryOptimizationRequest(context)
                            },
                            modifier = Modifier.testTag("battery_dialog_confirm_button")
                        ) {
                            Text(
                                text = stringResource(R.string.battery_dialog_confirm),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { viewModel.dismissBatteryPrompt() },
                            modifier = Modifier.testTag("battery_dialog_later_button")
                        ) {
                            Text(stringResource(R.string.later_btn))
                        }
                    }
                )
            }

            val availableUpdate = (updateUiState as? UpdateUiState.UpdateAvailable)?.info
            if (availableUpdate != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdateDialog() },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = {
                        Text(stringResource(R.string.update_available_title, availableUpdate.latestVersion))
                    },
                    text = {
                        Text(
                            text = availableUpdate.releaseNotes.ifBlank { availableUpdate.htmlUrl },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.dismissUpdateDialog()
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(availableUpdate.downloadUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.testTag("update_download_button")
                        ) {
                            Text(
                                text = stringResource(R.string.update_download_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                            Text(stringResource(R.string.later_btn))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatsoAuthenticatedShell(
    viewModel: ChatsoViewModel,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val identityState by viewModel.identityState.collectAsStateWithLifecycle()
    val identity = identityState ?: return

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val peers by viewModel.peersState.collectAsStateWithLifecycle()
    val connectedPeerIds by viewModel.connectedPeerIds.collectAsStateWithLifecycle()
    val foregroundPeerIds by viewModel.foregroundPeerIds.collectAsStateWithLifecycle()
    val typingPeerIds by viewModel.typingPeers.collectAsStateWithLifecycle()
    val activeTransfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val boundPort by viewModel.boundPort.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningNetwork.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val activePeerId by viewModel.activePeerId.collectAsStateWithLifecycle()
    val activePeer by viewModel.activePeer.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val currentMessageLimit by viewModel.currentMessageLimit.collectAsStateWithLifecycle()
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()
    val voiceRecordingState by viewModel.voiceRecordingState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()
    val favoriteStickers by viewModel.favoriteStickers.collectAsStateWithLifecycle()
    val unreadDivider by viewModel.unreadDivider.collectAsStateWithLifecycle()
    val serverRunning by viewModel.serverRunning.collectAsStateWithLifecycle()
    val totalMessageCount by viewModel.totalMessageCount.collectAsStateWithLifecycle()
    val devOnlineFakePeers by viewModel.devOnlineFakePeers.collectAsStateWithLifecycle()
    val devAutoReplyEnabled by viewModel.devAutoReplyEnabled.collectAsStateWithLifecycle()
    val scheduledFakeDelay by viewModel.scheduledFakeDelayRemainingSec.collectAsStateWithLifecycle()
    val bridgeTelemetry by viewModel.bridgeTelemetry.collectAsStateWithLifecycle()
    val isRunningBridgeTest by viewModel.isRunningBridgeTest.collectAsStateWithLifecycle()

    var showDevTools by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedTab, identity.devModeEnabled) {
        if (selectedTab != HomeTab.SETTINGS || !identity.devModeEnabled) {
            showDevTools = false
        }
    }

    val onChangeLanguage = { lang: com.example.data.AppLanguage ->
        viewModel.setLanguage(lang) {
            activity?.recreate()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

        BackHandler(enabled = !isExpanded && activePeerId != null) {
            viewModel.closeConversation()
        }
        BackHandler(enabled = activePeerId == null && selectedTab == HomeTab.SETTINGS && showDevTools) {
            showDevTools = false
        }
        BackHandler(enabled = activePeerId == null && !(selectedTab == HomeTab.SETTINGS && showDevTools) && selectedTab != HomeTab.CHATS) {
            viewModel.selectTab(HomeTab.CHATS)
        }

        if (isExpanded) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationRailItem(
                            selected = selectedTab == HomeTab.CHATS,
                            onClick = { viewModel.selectTab(HomeTab.CHATS) },
                            icon = {
                                Icon(
                                    if (selectedTab == HomeTab.CHATS) Icons.Filled.Forum else Icons.Outlined.Forum,
                                    contentDescription = stringResource(R.string.tab_chats)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.tab_chats),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_chats")
                        )
                        NavigationRailItem(
                            selected = selectedTab == HomeTab.CONNECT,
                            onClick = { viewModel.selectTab(HomeTab.CONNECT) },
                            icon = {
                                Icon(
                                    if (selectedTab == HomeTab.CONNECT) Icons.Filled.Lan else Icons.Outlined.Lan,
                                    contentDescription = stringResource(R.string.tab_connect)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.tab_connect),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_connect")
                        )
                        NavigationRailItem(
                            selected = selectedTab == HomeTab.PROFILE,
                            onClick = { viewModel.selectTab(HomeTab.PROFILE) },
                            icon = {
                                Icon(
                                    if (selectedTab == HomeTab.PROFILE) Icons.Filled.Person else Icons.Outlined.Person,
                                    contentDescription = stringResource(R.string.tab_profile)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.tab_profile),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_profile")
                        )
                        NavigationRailItem(
                            selected = selectedTab == HomeTab.SETTINGS,
                            onClick = { viewModel.selectTab(HomeTab.SETTINGS) },
                            icon = {
                                Icon(
                                    if (selectedTab == HomeTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.tab_settings)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.tab_settings),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_tab_settings")
                        )
                    }

                    VerticalDivider()

                    if (selectedTab == HomeTab.CHATS) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                        ) {
                            ChatsListScreen(
                                identity = identity,
                                peers = peers,
                                connectedPeerIds = connectedPeerIds,
                                foregroundPeerIds = foregroundPeerIds,
                                typingPeerIds = typingPeerIds,
                                selectedPeerId = activePeerId,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onOpenPeer = viewModel::openConversation,
                                onGoToConnect = { viewModel.selectTab(HomeTab.CONNECT) },
                                onQuickTestLocalhost = {
                                    viewModel.connectByManualIp("127.0.0.1", boundPort.toString())
                                },
                                onOpenProfile = { viewModel.selectTab(HomeTab.PROFILE) }
                            )
                        }
                        VerticalDivider()
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val peer = activePeer
                            if (peer != null) {
                                val presence = when {
                                    !connectedPeerIds.contains(peer.peerId) -> PeerPresence.OFFLINE
                                    foregroundPeerIds.contains(peer.peerId) -> PeerPresence.ONLINE
                                    else -> PeerPresence.BACKGROUND
                                }
                                ChatDetailScreen(
                                    identity = identity,
                                    peer = peer,
                                    messages = activeMessages,
                                    currentMessageLimit = currentMessageLimit,
                                    presence = presence,
                                    isPeerTyping = typingPeerIds.contains(peer.peerId),
                                    activeTransfers = activeTransfers,
                                    favoriteStickers = favoriteStickers,
                                    nowClockFlow = viewModel.nowClockMs,
                                    voiceRecordingState = voiceRecordingState,
                                    voicePlaybackState = voicePlaybackState,
                                    unreadDividerAnchorId = unreadDivider?.takeIf { it.peerId == peer.peerId }?.anchorMessageId,
                                    unreadDividerCount = unreadDivider?.takeIf { it.peerId == peer.peerId }?.unreadCount ?: 0,
                                    bridgePeerName = peers.firstOrNull { it.peerId == peer.viaBridgePeerId }?.username
                                        ?: if (peer.viaBridgePeerId == "dev_bridge_sandbox") {
                                            stringResource(R.string.dev_bridge_sandbox_name)
                                        } else {
                                            null
                                        },
                                    showBackButton = false,
                                    onBack = viewModel::closeConversation,
                                    onAtBottomChanged = viewModel::setActiveChatAtBottom,
                                    onMarkVisibleMessagesRead = viewModel::markVisibleMessagesRead,
                                    onLoadMoreMessages = viewModel::loadMoreMessages,
                                    onSendText = viewModel::sendTextMessage,
                                    onToggleReaction = viewModel::toggleReaction,
                                    onTypingChanged = viewModel::notifyTyping,
                                    onSendPhotoUri = viewModel::sendPhotoUri,
                                    onSendVideoUri = viewModel::sendVideoUri,
                                    onSendDocumentUri = viewModel::sendDocumentUri,
                                    onSendStickerStream = viewModel::sendStickerStream,
                                    onCheckStickerFavorited = viewModel::isMessageStickerFavorited,
                                    onToggleFavoriteSticker = viewModel::toggleFavoriteSticker,
                                    onRemoveFavoriteSticker = viewModel::removeFavoriteSticker,
                                    onSendFavoriteSticker = viewModel::sendFavoriteSticker,
                                    onStartVoiceRecording = viewModel::startVoiceRecording,
                                    onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                                    onSendVoiceRecording = viewModel::finishAndSendVoiceRecording,
                                    onToggleVoicePlayback = viewModel::toggleVoicePlayback,
                                    onOpenAttachment = viewModel::openAttachmentExternally,
                                    onCancelTransfer = viewModel::cancelTransfer,
                                    onRetryDownload = viewModel::retryDownload,
                                    onSetSelfDestruct = viewModel::setSelfDestructSeconds,
                                    onVerifyPeerKey = viewModel::verifyActivePeerKey,
                                    onReconnect = viewModel::reconnectActivePeer,
                                    onDeleteMessage = viewModel::deleteSingleMessage,
                                    onDeleteConversation = viewModel::deleteActiveConversation,
                                    onNotify = viewModel::postMessage
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.select_chat_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (selectedTab == HomeTab.CONNECT) {
                        Box(modifier = Modifier.weight(1f)) {
                            ConnectScreen(
                                identity = identity,
                                endpoints = endpoints,
                                boundPort = boundPort,
                                discoveredPeers = discoveredPeers,
                                isScanning = isScanning,
                                isConnecting = isConnecting,
                                onConnectManualIp = viewModel::connectByManualIp,
                                onConnectDiscoveredPeer = viewModel::connectToDiscoveredPeer,
                                onConnectQrRaw = viewModel::connectFromQrString,
                                onDecodeQrFromImage = viewModel::decodeQrFromGalleryUri,
                                onScanNetwork = viewModel::scanLanAndVpn,
                                onRotateQrToken = viewModel::rotateQrToken,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onNotify = viewModel::postMessage
                            )
                        }
                    } else if (selectedTab == HomeTab.PROFILE) {
                        Box(modifier = Modifier.weight(1f)) {
                            ProfileScreen(
                                identity = identity,
                                onSaveProfile = viewModel::updateProfile,
                                onNotify = viewModel::postMessage
                            )
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            if (showDevTools && identity.devModeEnabled) {
                                DevModeScreen(
                                    identity = identity,
                                    peers = peers,
                                    endpoints = endpoints,
                                    boundPort = boundPort,
                                    serverRunning = serverRunning,
                                    totalMessageCount = totalMessageCount,
                                    devOnlineFakePeers = devOnlineFakePeers,
                                    devAutoReplyEnabled = devAutoReplyEnabled,
                                    scheduledDelayRemainingSec = scheduledFakeDelay,
                                    bridgeTelemetry = bridgeTelemetry,
                                    isRunningBridgeTest = isRunningBridgeTest,
                                    onBack = { showDevTools = false },
                                    onCreateFakePeer = viewModel::devCreateFakePeer,
                                    onCreateBridgedFakePeer = viewModel::devCreateBridgedFakePeer,
                                    onSendFakeIncomingText = viewModel::devSendFakeIncomingMessage,
                                    onSendFakeUnreadBurst = viewModel::devSendFakeUnreadBurst,
                                    onSendFakeMedia = viewModel::devSendFakeIncomingMedia,
                                    onSimulateTyping = viewModel::devSimulateTyping,
                                    onSimulateReaction = { peerId -> viewModel.devSimulateReaction(peerId) },
                                    onSimulateReadReceipt = viewModel::devMarkOutgoingMessagesRead,
                                    onTriggerKeyWarning = viewModel::devTriggerKeyChangeWarning,
                                    onTogglePeerSimulatedOnline = viewModel::devSetPeerSimulatedOnline,
                                    onToggleAutoReply = viewModel::devSetAutoReplyEnabled,
                                    onToggleBridgeMode = viewModel::toggleBridgeMode,
                                    onRunLoopbackBridgeTest = viewModel::devRunLoopbackBridgeTest,
                                    onResetBridgeStats = viewModel::devResetBridgeTelemetry,
                                    onOpenPeerChat = { peerId ->
                                        showDevTools = false
                                        viewModel.selectTab(HomeTab.CHATS)
                                        viewModel.openConversation(peerId)
                                    },
                                    onClearFakePeers = viewModel::devClearFakePeers,
                                    onNotify = viewModel::postMessage
                                )
                            } else {
                                SettingsScreen(
                                    identity = identity,
                                    endpoints = endpoints,
                                    boundPort = boundPort,
                                    onToggleDiscoverable = viewModel::toggleDiscoverable,
                                    onToggleBlockUnknown = viewModel::toggleBlockUnknownPeers,
                                    onToggleBackgroundConnection = viewModel::toggleBackgroundConnection,
                                    onUpdateListenPort = viewModel::updateListenPort,
                                    onChangeTheme = viewModel::setThemeMode,
                                    onChangePalette = viewModel::setThemePalette,
                                    onToggleThemeAdvanced = viewModel::setThemeAdvanced,
                                    onChangeLanguage = onChangeLanguage,
                                    onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                                    isCheckingUpdates = updateUiState is UpdateUiState.Checking,
                                    onToggleDevMode = viewModel::setDevModeEnabled,
                                    onOpenDevTools = { showDevTools = true },
                                    onToggleBridgeMode = viewModel::toggleBridgeMode
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val currentOpenPeer = activePeer
            if (activePeerId != null && currentOpenPeer != null) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        val presence = when {
                            !connectedPeerIds.contains(currentOpenPeer.peerId) -> PeerPresence.OFFLINE
                            foregroundPeerIds.contains(currentOpenPeer.peerId) -> PeerPresence.ONLINE
                            else -> PeerPresence.BACKGROUND
                        }
                        ChatDetailScreen(
                            identity = identity,
                            peer = currentOpenPeer,
                            messages = activeMessages,
                            currentMessageLimit = currentMessageLimit,
                            presence = presence,
                            isPeerTyping = typingPeerIds.contains(currentOpenPeer.peerId),
                            activeTransfers = activeTransfers,
                            favoriteStickers = favoriteStickers,
                            nowClockFlow = viewModel.nowClockMs,
                            voiceRecordingState = voiceRecordingState,
                            voicePlaybackState = voicePlaybackState,
                            unreadDividerAnchorId = unreadDivider?.takeIf { it.peerId == currentOpenPeer.peerId }?.anchorMessageId,
                            unreadDividerCount = unreadDivider?.takeIf { it.peerId == currentOpenPeer.peerId }?.unreadCount ?: 0,
                            bridgePeerName = peers.firstOrNull { it.peerId == currentOpenPeer.viaBridgePeerId }?.username
                                ?: if (currentOpenPeer.viaBridgePeerId == "dev_bridge_sandbox") {
                                    stringResource(R.string.dev_bridge_sandbox_name)
                                } else {
                                    null
                                },
                            showBackButton = true,
                            onBack = viewModel::closeConversation,
                            onAtBottomChanged = viewModel::setActiveChatAtBottom,
                            onMarkVisibleMessagesRead = viewModel::markVisibleMessagesRead,
                            onLoadMoreMessages = viewModel::loadMoreMessages,
                            onSendText = viewModel::sendTextMessage,
                            onToggleReaction = viewModel::toggleReaction,
                            onTypingChanged = viewModel::notifyTyping,
                            onSendPhotoUri = viewModel::sendPhotoUri,
                            onSendVideoUri = viewModel::sendVideoUri,
                            onSendDocumentUri = viewModel::sendDocumentUri,
                            onSendStickerStream = viewModel::sendStickerStream,
                            onCheckStickerFavorited = viewModel::isMessageStickerFavorited,
                            onToggleFavoriteSticker = viewModel::toggleFavoriteSticker,
                            onRemoveFavoriteSticker = viewModel::removeFavoriteSticker,
                            onSendFavoriteSticker = viewModel::sendFavoriteSticker,
                            onStartVoiceRecording = viewModel::startVoiceRecording,
                            onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                            onSendVoiceRecording = viewModel::finishAndSendVoiceRecording,
                            onToggleVoicePlayback = viewModel::toggleVoicePlayback,
                            onOpenAttachment = viewModel::openAttachmentExternally,
                            onCancelTransfer = viewModel::cancelTransfer,
                            onRetryDownload = viewModel::retryDownload,
                            onSetSelfDestruct = viewModel::setSelfDestructSeconds,
                            onVerifyPeerKey = viewModel::verifyActivePeerKey,
                            onReconnect = viewModel::reconnectActivePeer,
                            onDeleteMessage = viewModel::deleteSingleMessage,
                            onDeleteConversation = viewModel::deleteActiveConversation,
                            onNotify = viewModel::postMessage
                        )
                    }
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == HomeTab.CHATS,
                                onClick = { viewModel.selectTab(HomeTab.CHATS) },
                                icon = {
                                    Icon(
                                        if (selectedTab == HomeTab.CHATS) Icons.Filled.Forum else Icons.Outlined.Forum,
                                        contentDescription = stringResource(R.string.tab_chats)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.tab_chats),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_chats")
                            )
                            NavigationBarItem(
                                selected = selectedTab == HomeTab.CONNECT,
                                onClick = { viewModel.selectTab(HomeTab.CONNECT) },
                                icon = {
                                    Icon(
                                        if (selectedTab == HomeTab.CONNECT) Icons.Filled.Lan else Icons.Outlined.Lan,
                                        contentDescription = stringResource(R.string.tab_connect)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.tab_connect),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_connect")
                            )
                            NavigationBarItem(
                                selected = selectedTab == HomeTab.PROFILE,
                                onClick = { viewModel.selectTab(HomeTab.PROFILE) },
                                icon = {
                                    Icon(
                                        if (selectedTab == HomeTab.PROFILE) Icons.Filled.Person else Icons.Outlined.Person,
                                        contentDescription = stringResource(R.string.tab_profile)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.tab_profile),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_profile")
                            )
                            NavigationBarItem(
                                selected = selectedTab == HomeTab.SETTINGS,
                                onClick = { viewModel.selectTab(HomeTab.SETTINGS) },
                                icon = {
                                    Icon(
                                        if (selectedTab == HomeTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.tab_settings)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.tab_settings),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_settings")
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            HomeTab.CHATS -> ChatsListScreen(
                                identity = identity,
                                peers = peers,
                                connectedPeerIds = connectedPeerIds,
                                foregroundPeerIds = foregroundPeerIds,
                                typingPeerIds = typingPeerIds,
                                selectedPeerId = activePeerId,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onOpenPeer = viewModel::openConversation,
                                onGoToConnect = { viewModel.selectTab(HomeTab.CONNECT) },
                                onQuickTestLocalhost = {
                                    viewModel.connectByManualIp("127.0.0.1", boundPort.toString())
                                },
                                onOpenProfile = { viewModel.selectTab(HomeTab.PROFILE) }
                            )
                            HomeTab.CONNECT -> ConnectScreen(
                                identity = identity,
                                endpoints = endpoints,
                                boundPort = boundPort,
                                discoveredPeers = discoveredPeers,
                                isScanning = isScanning,
                                isConnecting = isConnecting,
                                onConnectManualIp = viewModel::connectByManualIp,
                                onConnectDiscoveredPeer = viewModel::connectToDiscoveredPeer,
                                onConnectQrRaw = viewModel::connectFromQrString,
                                onDecodeQrFromImage = viewModel::decodeQrFromGalleryUri,
                                onScanNetwork = viewModel::scanLanAndVpn,
                                onRotateQrToken = viewModel::rotateQrToken,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onNotify = viewModel::postMessage
                            )
                            HomeTab.PROFILE -> ProfileScreen(
                                identity = identity,
                                onSaveProfile = viewModel::updateProfile,
                                onNotify = viewModel::postMessage
                            )
                            HomeTab.SETTINGS -> {
                                if (showDevTools && identity.devModeEnabled) {
                                    DevModeScreen(
                                        identity = identity,
                                        peers = peers,
                                        endpoints = endpoints,
                                        boundPort = boundPort,
                                        serverRunning = serverRunning,
                                        totalMessageCount = totalMessageCount,
                                        devOnlineFakePeers = devOnlineFakePeers,
                                        devAutoReplyEnabled = devAutoReplyEnabled,
                                        scheduledDelayRemainingSec = scheduledFakeDelay,
                                        bridgeTelemetry = bridgeTelemetry,
                                        isRunningBridgeTest = isRunningBridgeTest,
                                        onBack = { showDevTools = false },
                                        onCreateFakePeer = viewModel::devCreateFakePeer,
                                        onCreateBridgedFakePeer = viewModel::devCreateBridgedFakePeer,
                                        onSendFakeIncomingText = viewModel::devSendFakeIncomingMessage,
                                        onSendFakeUnreadBurst = viewModel::devSendFakeUnreadBurst,
                                        onSendFakeMedia = viewModel::devSendFakeIncomingMedia,
                                        onSimulateTyping = viewModel::devSimulateTyping,
                                        onSimulateReaction = { peerId -> viewModel.devSimulateReaction(peerId) },
                                        onSimulateReadReceipt = viewModel::devMarkOutgoingMessagesRead,
                                        onTriggerKeyWarning = viewModel::devTriggerKeyChangeWarning,
                                        onTogglePeerSimulatedOnline = viewModel::devSetPeerSimulatedOnline,
                                        onToggleAutoReply = viewModel::devSetAutoReplyEnabled,
                                        onToggleBridgeMode = viewModel::toggleBridgeMode,
                                        onRunLoopbackBridgeTest = viewModel::devRunLoopbackBridgeTest,
                                        onResetBridgeStats = viewModel::devResetBridgeTelemetry,
                                        onOpenPeerChat = { peerId ->
                                            showDevTools = false
                                            viewModel.selectTab(HomeTab.CHATS)
                                            viewModel.openConversation(peerId)
                                        },
                                        onClearFakePeers = viewModel::devClearFakePeers,
                                        onNotify = viewModel::postMessage
                                    )
                                } else {
                                    SettingsScreen(
                                        identity = identity,
                                        endpoints = endpoints,
                                        boundPort = boundPort,
                                        onToggleDiscoverable = viewModel::toggleDiscoverable,
                                        onToggleBlockUnknown = viewModel::toggleBlockUnknownPeers,
                                        onToggleBackgroundConnection = viewModel::toggleBackgroundConnection,
                                        onUpdateListenPort = viewModel::updateListenPort,
                                        onChangeTheme = viewModel::setThemeMode,
                                        onChangePalette = viewModel::setThemePalette,
                                        onToggleThemeAdvanced = viewModel::setThemeAdvanced,
                                        onChangeLanguage = onChangeLanguage,
                                        onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                                        isCheckingUpdates = updateUiState is UpdateUiState.Checking,
                                        onToggleDevMode = viewModel::setDevModeEnabled,
                                        onOpenDevTools = { showDevTools = true },
                                        onToggleBridgeMode = viewModel::toggleBridgeMode
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
