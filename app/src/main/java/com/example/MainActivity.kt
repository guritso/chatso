package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.LocaleHelper
import com.example.data.ThemeMode
import com.example.ui.ChatsoViewModel
import com.example.ui.HomeTab
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.ConnectScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.theme.ChatsoTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatsoRootApp()
        }
    }
}

@Composable
fun ChatsoRootApp(viewModel: ChatsoViewModel = viewModel()) {
    val identity by viewModel.identityState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()

    val isDarkTheme = when (identity?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> systemDark
    }

    ChatsoTheme(darkTheme = isDarkTheme) {
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
    val typingPeerIds by viewModel.typingPeers.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val boundPort by viewModel.boundPort.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningNetwork.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val activePeerId by viewModel.activePeerId.collectAsStateWithLifecycle()
    val activePeer by viewModel.activePeer.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val nowClockMs by viewModel.nowClockMs.collectAsStateWithLifecycle()
    val voiceRecordingState by viewModel.voiceRecordingState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()

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
        BackHandler(enabled = activePeerId == null && selectedTab != HomeTab.CHATS) {
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
                                typingPeerIds = typingPeerIds,
                                selectedPeerId = activePeerId,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onOpenPeer = viewModel::openConversation,
                                onGoToConnect = { viewModel.selectTab(HomeTab.CONNECT) },
                                onQuickTestLocalhost = {
                                    viewModel.connectByManualIp("127.0.0.1", boundPort.toString())
                                }
                            )
                        }
                        VerticalDivider()
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val peer = activePeer
                            if (peer != null) {
                                ChatDetailScreen(
                                    identity = identity,
                                    peer = peer,
                                    messages = activeMessages,
                                    isConnected = connectedPeerIds.contains(peer.peerId),
                                    isPeerTyping = typingPeerIds.contains(peer.peerId),
                                    nowClockMs = nowClockMs,
                                    voiceRecordingState = voiceRecordingState,
                                    voicePlaybackState = voicePlaybackState,
                                    showBackButton = false,
                                    onBack = viewModel::closeConversation,
                                    onSendText = viewModel::sendTextMessage,
                                    onTypingChanged = viewModel::notifyTyping,
                                    onSendPhotoUri = viewModel::sendPhotoUri,
                                    onSendDocumentUri = viewModel::sendDocumentUri,
                                    onStartVoiceRecording = viewModel::startVoiceRecording,
                                    onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                                    onSendVoiceRecording = viewModel::finishAndSendVoiceRecording,
                                    onToggleVoicePlayback = viewModel::toggleVoicePlayback,
                                    onOpenAttachment = viewModel::openAttachmentExternally,
                                    onSetSelfDestruct = viewModel::setSelfDestructSeconds,
                                    onVerifyPeerKey = viewModel::verifyActivePeerKey,
                                    onReconnect = viewModel::reconnectActivePeer,
                                    onDeleteConversation = viewModel::deleteActiveConversation
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
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            ProfileSettingsScreen(
                                identity = identity,
                                endpoints = endpoints,
                                boundPort = boundPort,
                                onSaveProfile = viewModel::updateProfile,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onToggleBlockUnknown = viewModel::toggleBlockUnknownPeers,
                                onUpdateListenPort = viewModel::updateListenPort,
                                onChangeTheme = viewModel::setThemeMode,
                                onChangeLanguage = onChangeLanguage,
                                onNotify = viewModel::postMessage
                            )
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
                        ChatDetailScreen(
                            identity = identity,
                            peer = currentOpenPeer,
                            messages = activeMessages,
                            isConnected = connectedPeerIds.contains(currentOpenPeer.peerId),
                            isPeerTyping = typingPeerIds.contains(currentOpenPeer.peerId),
                            nowClockMs = nowClockMs,
                            voiceRecordingState = voiceRecordingState,
                            voicePlaybackState = voicePlaybackState,
                            showBackButton = true,
                            onBack = viewModel::closeConversation,
                            onSendText = viewModel::sendTextMessage,
                            onTypingChanged = viewModel::notifyTyping,
                            onSendPhotoUri = viewModel::sendPhotoUri,
                            onSendDocumentUri = viewModel::sendDocumentUri,
                            onStartVoiceRecording = viewModel::startVoiceRecording,
                            onCancelVoiceRecording = viewModel::cancelVoiceRecording,
                            onSendVoiceRecording = viewModel::finishAndSendVoiceRecording,
                            onToggleVoicePlayback = viewModel::toggleVoicePlayback,
                            onOpenAttachment = viewModel::openAttachmentExternally,
                            onSetSelfDestruct = viewModel::setSelfDestructSeconds,
                            onVerifyPeerKey = viewModel::verifyActivePeerKey,
                            onReconnect = viewModel::reconnectActivePeer,
                            onDeleteConversation = viewModel::deleteActiveConversation
                        )
                    }
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
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
                                typingPeerIds = typingPeerIds,
                                selectedPeerId = activePeerId,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onOpenPeer = viewModel::openConversation,
                                onGoToConnect = { viewModel.selectTab(HomeTab.CONNECT) },
                                onQuickTestLocalhost = {
                                    viewModel.connectByManualIp("127.0.0.1", boundPort.toString())
                                }
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
                            HomeTab.PROFILE -> ProfileSettingsScreen(
                                identity = identity,
                                endpoints = endpoints,
                                boundPort = boundPort,
                                onSaveProfile = viewModel::updateProfile,
                                onToggleDiscoverable = viewModel::toggleDiscoverable,
                                onToggleBlockUnknown = viewModel::toggleBlockUnknownPeers,
                                onUpdateListenPort = viewModel::updateListenPort,
                                onChangeTheme = viewModel::setThemeMode,
                                onChangeLanguage = onChangeLanguage,
                                onNotify = viewModel::postMessage
                            )
                        }
                    }
                }
            }
        }
    }
}
