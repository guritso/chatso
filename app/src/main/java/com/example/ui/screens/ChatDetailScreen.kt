package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.crypto.CryptoEngine
import com.example.data.ActiveTransferInfo
import com.example.data.FavoriteStickerEntity
import com.example.data.LocalIdentity
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.data.SelfDestructOption
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecordingState
import com.example.network.NetworkUtils
import com.example.ui.components.AttachmentsMenuTray
import com.example.ui.components.ChatMessageBubbleItem
import com.example.ui.components.ChatsoAvatar
import com.example.ui.components.ComposerTrayMode
import com.example.ui.components.FloatingTransfersOverlay
import com.example.ui.components.FullScreenImageViewerDialog
import com.example.ui.components.FullScreenVideoPlayerDialog
import com.example.ui.components.MessageActionsReactionDialog
import com.example.ui.components.StickersTrayContent
import com.example.ui.components.UnreadMessagesDivider
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import java.io.InputStream
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
private fun SelfDestructOption.localizedLabel(): String = when (this) {
    SelfDestructOption.OFF -> stringResource(R.string.timer_off)
    SelfDestructOption.SEC_30 -> stringResource(R.string.timer_30s)
    SelfDestructOption.MIN_5 -> stringResource(R.string.timer_5m)
    SelfDestructOption.HOUR_1 -> stringResource(R.string.timer_1h)
}

private enum class FullscreenViewerTarget {
    IMAGE, VIDEO
}

private data class ViewerMediaPayload(
    val kind: FullscreenViewerTarget,
    val path: String,
    val fileName: String?
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatDetailScreen(
    identity: LocalIdentity,
    peer: PeerEntity,
    messages: List<MessageEntity>,
    currentMessageLimit: Int,
    presence: PeerPresence,
    isPeerTyping: Boolean,
    activeTransfers: List<ActiveTransferInfo>,
    favoriteStickers: List<FavoriteStickerEntity>,
    nowClockFlow: StateFlow<Long>,
    voiceRecordingState: VoiceRecordingState,
    voicePlaybackState: VoicePlaybackState,
    unreadDividerAnchorId: String? = null,
    unreadDividerCount: Int = 0,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onLoadMoreMessages: () -> Unit,
    onSendText: (String, MessageEntity?) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onSendPhotoUri: (Uri, MessageEntity?) -> Unit,
    onSendVideoUri: (Uri, MessageEntity?) -> Unit,
    onSendDocumentUri: (Uri, MessageEntity?) -> Unit,
    onSendStickerStream: (InputStream, String, String?, MessageEntity?) -> Unit,
    onCheckStickerFavorited: suspend (MessageEntity) -> Boolean,
    onToggleFavoriteSticker: (MessageEntity) -> Unit,
    onRemoveFavoriteSticker: (String) -> Unit,
    onSendFavoriteSticker: (FavoriteStickerEntity, MessageEntity?) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onSendVoiceRecording: (MessageEntity?) -> Unit,
    onToggleVoicePlayback: (messageId: String, localPath: String) -> Unit,
    onOpenAttachment: (localPath: String, mimeType: String?) -> Unit,
    onCancelTransfer: (messageId: String) -> Unit,
    onRetryDownload: (messageId: String) -> Unit,
    onSetSelfDestruct: (Int) -> Unit,
    onVerifyPeerKey: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteConversation: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val copiedMsg = stringResource(R.string.msg_copied)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }

    // Key LazyListState per peer to eliminate any flash/position bleed across chats
    val listState = key(peer.peerId) { rememberLazyListState() }
    var scrollToBottomPending by remember(peer.peerId) { mutableStateOf(false) }

    suspend fun scrollToBottomSmooth() {
        if (listState.firstVisibleItemIndex > 20) {
            listState.scrollToItem(15)
        }
        listState.animateScrollToItem(0)
    }

    fun markSendingAndScroll() {
        scrollToBottomPending = true
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            scope.launch { scrollToBottomSmooth() }
        }
    }

    val textFieldState = key(peer.peerId) { rememberTextFieldState() }
    val draftText = textFieldState.text.toString()
    LaunchedEffect(peer.peerId, draftText) {
        onTypingChanged(draftText.isNotBlank())
    }
    var replyingToId by rememberSaveable(peer.peerId) { mutableStateOf<String?>(null) }
    val replyingToMessage = remember(replyingToId, messages) {
        replyingToId?.let { id -> messages.firstOrNull { it.messageId == id } }
    }
    val latestReplyingTo by rememberUpdatedState(replyingToMessage)
    val latestSendSticker by rememberUpdatedState(onSendStickerStream)
    val receiveContentListener = remember(peer.peerId, context) {
        ReceiveContentListener { transferableContent ->
            if (!transferableContent.hasMediaType(MediaType.Image)) {
                return@ReceiveContentListener transferableContent
            }
            transferableContent.consume { item ->
                val uri = item.uri ?: return@consume false
                val cr = context.contentResolver
                val stream = runCatching { cr.openInputStream(uri) }.getOrNull()
                    ?: return@consume false
                val mimeType = cr.getType(uri)
                    ?: transferableContent.clipMetadata.clipDescription.let { desc ->
                        (0 until desc.mimeTypeCount)
                            .map { desc.getMimeType(it) }
                            .firstOrNull { it.startsWith("image/") }
                    }
                    ?: "image/webp"
                val fileName = runCatching {
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull()
                val replyTarget = latestReplyingTo
                replyingToId = null
                markSendingAndScroll()
                latestSendSticker(stream, mimeType, fileName, replyTarget)
                true
            }
        }
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showSelfDestructDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var viewerPayload by remember { mutableStateOf<ViewerMediaPayload?>(null) }
    var actionDialogMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var transfersExpanded by remember { mutableStateOf(false) }

    var activeTrayMode by rememberSaveable(peer.peerId) { mutableStateOf(ComposerTrayMode.NONE) }
    var lastVisibleTrayMode by rememberSaveable(peer.peerId) { mutableStateOf(ComposerTrayMode.STICKERS) }
    var overlayTrayForIme by remember(peer.peerId) { mutableStateOf<ComposerTrayMode?>(null) }
    var heldTrayHeightDp by remember(peer.peerId) { mutableStateOf(0.dp) }
    var lastKeyboardHeightDp by remember { mutableStateOf(296.dp) }
    var chatAreaHeightDp by remember { mutableStateOf(640.dp) }

    LaunchedEffect(activeTrayMode) {
        if (activeTrayMode != ComposerTrayMode.NONE) {
            lastVisibleTrayMode = activeTrayMode
        }
    }

    BackHandler(enabled = activeTrayMode != ComposerTrayMode.NONE) {
        overlayTrayForIme = null
        heldTrayHeightDp = 0.dp
        activeTrayMode = ComposerTrayMode.NONE
    }

    LaunchedEffect(activeTransfers.isEmpty()) {
        if (activeTransfers.isEmpty()) transfersExpanded = false
    }

    val newestMessage = messages.firstOrNull()
    val newestMessageId = newestMessage?.messageId
    var prevNewestMessageId by remember(peer.peerId) { mutableStateOf<String?>(null) }
    var lastAutoScrolledAnchor by remember(peer.peerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(peer.peerId, unreadDividerAnchorId, messages.size) {
        if (unreadDividerAnchorId != null && lastAutoScrolledAnchor != unreadDividerAnchorId) {
            val idx = messages.indexOfFirst { it.messageId == unreadDividerAnchorId }
            if (idx >= 0) {
                lastAutoScrolledAnchor = unreadDividerAnchorId
                if (idx > 2) {
                    listState.scrollToItem(idx)
                }
            }
        }
    }
    LaunchedEffect(newestMessageId) {
        val previous = prevNewestMessageId
        prevNewestMessageId = newestMessageId
        val isOutgoingNewest = newestMessage?.isOutgoing == true
        val shouldScroll = newestMessageId != null && (
            scrollToBottomPending ||
                (previous != null && previous != newestMessageId && (isOutgoingNewest || listState.firstVisibleItemIndex <= 2))
            )
        if (shouldScroll) {
            scrollToBottomPending = false
            scrollToBottomSmooth()
        }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > 240
        }
    }

    // Strict guard prevents pagination flicker when hitting the top of shorter chats
    val shouldLoadMore by remember(messages.size, currentMessageLimit) {
        derivedStateOf {
            val total = messages.size
            if (total < currentMessageLimit || total == 0) {
                false
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 15
            }
        }
    }
    LaunchedEffect(shouldLoadMore, messages.size, currentMessageLimit) {
        if (shouldLoadMore) {
            onLoadMoreMessages()
        }
    }

    // WhatsApp-style rubber-band spacing animation at overscroll boundaries
    val maxOverscrollPx = remember(density) { with(density) { 140.dp.toPx() } }
    val overscrollAnim = remember(peer.peerId) { Animatable(0f) }
    val nestedScrollConnection = remember(peer.peerId, maxOverscrollPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && abs(consumed.y) < 0.5f && abs(available.y) > 0.5f) {
                    val next = (overscrollAnim.value + abs(available.y) * 0.35f).coerceIn(0f, maxOverscrollPx)
                    scope.launch { overscrollAnim.snapTo(next) }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (overscrollAnim.value > 0f) {
                    overscrollAnim.animateTo(
                        0f,
                        spring(
                            dampingRatio = 0.72f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
                return Velocity.Zero
            }
        }
    }
    val extraSpacingDp = remember(overscrollAnim.value, density) {
        val fraction = (overscrollAnim.value / maxOverscrollPx).coerceIn(0f, 1f)
        (fraction * 22f).dp
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoiceRecording()
    }

    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
    val navBarBottomDp = with(density) { navBarBottomPx.toDp() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeTargetBottomPx = WindowInsets.imeAnimationTarget.getBottom(density)
    val effectiveImeDp = with(density) { (imeBottomPx - navBarBottomPx).coerceAtLeast(0).toDp() }
    val targetImeDp = with(density) { (imeTargetBottomPx - navBarBottomPx).coerceAtLeast(0).toDp() }

    LaunchedEffect(targetImeDp, effectiveImeDp, activeTrayMode, overlayTrayForIme) {
        if (targetImeDp > 120.dp) {
            lastKeyboardHeightDp = targetImeDp
        } else if (
            imeTargetBottomPx == 0 &&
            effectiveImeDp > 120.dp &&
            activeTrayMode == ComposerTrayMode.NONE &&
            overlayTrayForIme == null &&
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
        ) {
            lastKeyboardHeightDp = effectiveImeDp
        }
    }

    val compactMaxDp = (chatAreaHeightDp - 96.dp - navBarBottomDp).coerceAtLeast(220.dp)
    val compactTrayHeightDp = lastKeyboardHeightDp.coerceIn(200.dp, compactMaxDp)
    val targetTrayHeightDp = when (activeTrayMode) {
        ComposerTrayMode.NONE -> if (overlayTrayForIme != null) heldTrayHeightDp else 0.dp
        ComposerTrayMode.STICKERS,
        ComposerTrayMode.ATTACHMENTS -> compactTrayHeightDp
    }

    val trayHeightAnim = remember(peer.peerId) { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(activeTrayMode, overlayTrayForIme, targetTrayHeightDp) {
        if (activeTrayMode != ComposerTrayMode.NONE) {
            if (trayHeightAnim.value < 1.dp && (effectiveImeDp > 120.dp || targetImeDp > 120.dp)) {
                trayHeightAnim.snapTo(targetTrayHeightDp)
            } else {
                trayHeightAnim.animateTo(
                    targetValue = targetTrayHeightDp,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = 0.88f
                    )
                )
            }
        } else if (overlayTrayForIme != null) {
            if (trayHeightAnim.value > heldTrayHeightDp + 2.dp) {
                trayHeightAnim.animateTo(
                    targetValue = heldTrayHeightDp,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMedium,
                        dampingRatio = 0.92f
                    )
                )
            } else {
                trayHeightAnim.snapTo(heldTrayHeightDp)
            }
        } else {
            trayHeightAnim.animateTo(
                targetValue = 0.dp,
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    dampingRatio = 0.9f
                )
            )
        }
    }

    LaunchedEffect(overlayTrayForIme, effectiveImeDp, targetImeDp) {
        if (overlayTrayForIme == null) return@LaunchedEffect
        val expectedImeDp = if (targetImeDp > 120.dp) targetImeDp else heldTrayHeightDp
        if (effectiveImeDp > 120.dp && effectiveImeDp >= (expectedImeDp - 2.dp)) {
            delay(90L)
            overlayTrayForIme = null
            heldTrayHeightDp = 0.dp
            trayHeightAnim.snapTo(0.dp)
        }
    }

    LaunchedEffect(overlayTrayForIme) {
        if (overlayTrayForIme != null) {
            delay(1200L)
            if (overlayTrayForIme != null && effectiveImeDp < 40.dp) {
                overlayTrayForIme = null
                heldTrayHeightDp = 0.dp
            }
        }
    }

    fun switchToKeyboard() {
        val currentTray = activeTrayMode
        if (currentTray != ComposerTrayMode.NONE) {
            overlayTrayForIme = currentTray
            heldTrayHeightDp = compactTrayHeightDp
            activeTrayMode = ComposerTrayMode.NONE
        }
        inputFocusRequester.requestFocus()
        keyboardController?.show()
    }

    fun openTray(mode: ComposerTrayMode) {
        val exactIme = targetImeDp.takeIf { it > 120.dp }
            ?: effectiveImeDp.takeIf { it > 120.dp }
        if (exactIme != null) {
            lastKeyboardHeightDp = exactIme
        }
        val effectiveCompact = (exactIme ?: lastKeyboardHeightDp).coerceIn(200.dp, compactMaxDp)
        val wasShowingImeOrOverlay = (exactIme != null) || overlayTrayForIme != null
        overlayTrayForIme = null
        heldTrayHeightDp = 0.dp
        activeTrayMode = mode
        if (wasShowingImeOrOverlay) {
            scope.launch { trayHeightAnim.snapTo(effectiveCompact) }
        }
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun dismissTray() {
        overlayTrayForIme = null
        heldTrayHeightDp = 0.dp
        activeTrayMode = ComposerTrayMode.NONE
    }

    val isConnected = presence != PeerPresence.OFFLINE
    var composerOverlayHeightDp by remember { mutableStateOf(72.dp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Flat Header Bar matching chat canvas
        Surface(
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackButton) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }

                ChatsoAvatar(
                    name = peer.username,
                    avatarModel = peer.avatarPath,
                    size = 40.dp,
                    presence = presence,
                    showPresenceDot = true
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { if (!isConnected) onReconnect() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.username,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (peer.isMutuallyVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = stringResource(R.string.safety_title),
                                tint = OnlineEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Text(
                        text = when {
                            isPeerTyping -> stringResource(R.string.status_typing)
                            presence == PeerPresence.ONLINE -> stringResource(R.string.status_online)
                            presence == PeerPresence.BACKGROUND -> stringResource(R.string.status_background)
                            else -> stringResource(R.string.status_offline_tap)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isPeerTyping -> MaterialTheme.colorScheme.primary
                            presence == PeerPresence.ONLINE -> OnlineEmerald
                            presence == PeerPresence.BACKGROUND -> WarningAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!isConnected) {
                    IconButton(
                        onClick = onReconnect,
                        modifier = Modifier.testTag("chat_reconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.connect_btn)
                        )
                    }
                }

                IconButton(
                    onClick = { showSelfDestructDialog = true },
                    modifier = Modifier.testTag("chat_self_destruct_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.cd_self_destruct),
                        tint = if (peer.selfDestructSeconds > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(
                    onClick = { showSafetyDialog = true },
                    modifier = Modifier.testTag("chat_verify_key_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = stringResource(R.string.cd_verify_key),
                        tint = if (peer.isMutuallyVerified) {
                            OnlineEmerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_chat)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = null,
                                    tint = DangerCoral
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                onDeleteConversation()
                            }
                        )
                    }
                }
            }
        }

        if (peer.keyChangedWarning) {
            Surface(
                color = WarningAmber.copy(alpha = 0.18f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSafetyDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.key_changed_warning),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (peer.selfDestructSeconds > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        val timerLabel = SelfDestructOption.fromSeconds(peer.selfDestructSeconds).localizedLabel()
                        Text(
                            text = "${stringResource(R.string.self_destruct_title)}: $timerLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Chat Feed + Floating Composer + Keyboard-Behind Trays + Floating Top-Right Transfers Bubble
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    chatAreaHeightDp = with(density) { size.height.toDp() }
                }
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .pointerInput(peer.peerId) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val allUp = event.changes.all { !it.pressed }
                                    if (allUp && overscrollAnim.value > 0f) {
                                        scope.launch {
                                            overscrollAnim.animateTo(
                                                0f,
                                                spring(
                                                    dampingRatio = 0.72f,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        .testTag("chat_messages_list"),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = composerOverlayHeightDp + 6.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp + extraSpacingDp)
                ) {
                    items(
                        items = messages,
                        key = { it.messageId },
                        contentType = { it.type }
                    ) { message ->
                        val renderBubble: @Composable () -> Unit = {
                            ChatMessageBubbleItem(
                                message = message,
                                peerUsername = peer.username,
                                nowClockFlow = nowClockFlow,
                                voicePlaybackState = voicePlaybackState,
                                onToggleVoice = onToggleVoicePlayback,
                                onOpenImageFullscreen = { path, fileName ->
                                    viewerPayload = ViewerMediaPayload(FullscreenViewerTarget.IMAGE, path, fileName)
                                },
                                onOpenVideoFullscreen = { path, fileName ->
                                    viewerPayload = ViewerMediaPayload(FullscreenViewerTarget.VIDEO, path, fileName)
                                },
                                onOpenAttachment = onOpenAttachment,
                                onCancelTransfer = onCancelTransfer,
                                onRetryDownload = onRetryDownload,
                                onSwipeReply = { target -> replyingToId = target.messageId },
                                onLongPressMessage = { target -> actionDialogMessage = target },
                                onReactionChipClick = { msgId, emoji -> onToggleReaction(msgId, emoji) },
                                onQuoteClick = { targetId ->
                                    val idx = messages.indexOfFirst { it.messageId == targetId }
                                    if (idx >= 0) {
                                        scope.launch { listState.animateScrollToItem(idx) }
                                    }
                                }
                            )
                        }
                        if (unreadDividerAnchorId != null && message.messageId == unreadDividerAnchorId) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                UnreadMessagesDivider(unreadCount = unreadDividerCount)
                                Spacer(modifier = Modifier.height(6.dp))
                                renderBubble()
                            }
                        } else {
                            renderBubble()
                        }
                    }
                }
            }

            // Floating Top-Right Circular Transfer Bubble
            FloatingTransfersOverlay(
                transfers = activeTransfers,
                expanded = transfersExpanded,
                onToggleExpanded = { transfersExpanded = !transfersExpanded },
                onCollapse = { transfersExpanded = false },
                onCancelTransfer = onCancelTransfer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            )

            // Floating Slim Borderless Composer Pill + Standalone Round Mic/Send Button + Keyboard-Behind Drawer
            val displayedTrayDp = trayHeightAnim.value
            val immediateOpenFloorDp = if (
                activeTrayMode != ComposerTrayMode.NONE &&
                trayHeightAnim.value < 1.dp &&
                (effectiveImeDp > 120.dp || targetImeDp > 120.dp)
            ) {
                compactTrayHeightDp
            } else {
                0.dp
            }
            val heldFloorDp = if (overlayTrayForIme != null) {
                maxOf(heldTrayHeightDp, displayedTrayDp)
            } else {
                0.dp
            }
            val activeTrayDp = if (activeTrayMode != ComposerTrayMode.NONE || displayedTrayDp > 0.5.dp) {
                displayedTrayDp
            } else {
                0.dp
            }
            val bottomSpaceDp = maxOf(effectiveImeDp, activeTrayDp, immediateOpenFloorDp, heldFloorDp)

            // Floating Scroll-to-Bottom Button when scrolled up in chat history
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = composerOverlayHeightDp + 10.dp)
            ) {
                Surface(
                    onClick = { scope.launch { scrollToBottomSmooth() } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 4.dp,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_scroll_to_bottom_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        composerOverlayHeightDp = with(density) { size.height.toDp() }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (voiceRecordingState.isRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(26.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                shadowElevation = 4.dp,
                                tonalElevation = 2.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 46.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(
                                        onClick = onCancelVoiceRecording,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("voice_cancel_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.close_btn),
                                            tint = DangerCoral
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(DangerCoral)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${stringResource(R.string.recording_voice)} · ${NetworkUtils.formatDurationMs(voiceRecordingState.elapsedMs)}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                            }

                            Surface(
                                onClick = {
                                    val replyTarget = replyingToMessage
                                    replyingToId = null
                                    markSendingAndScroll()
                                    onSendVoiceRecording(replyTarget)
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("voice_send_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.send_btn),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                shadowElevation = 4.dp,
                                tonalElevation = 2.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    if (replyingToMessage != null) {
                                        val replyAuthor = if (replyingToMessage.isOutgoing) {
                                            stringResource(R.string.reply_you)
                                        } else {
                                            peer.username
                                        }
                                        val replySnippet = when (replyingToMessage.type) {
                                            MessageType.IMAGE.name -> "Photo"
                                            MessageType.VIDEO.name -> stringResource(R.string.video_label)
                                            MessageType.STICKER.name -> stringResource(R.string.sticker_label)
                                            MessageType.VOICE.name -> stringResource(R.string.voice_message_label)
                                            MessageType.FILE.name -> replyingToMessage.fileName ?: "File"
                                            else -> replyingToMessage.textContent
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .height(28.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.replying_to, replyAuthor),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = replySnippet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = { replyingToId = null },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("reply_preview_close")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = stringResource(R.string.close_btn),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 44.dp)
                                            .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isStickersOpen = activeTrayMode == ComposerTrayMode.STICKERS
                                        IconButton(
                                            onClick = {
                                                if (isStickersOpen) {
                                                    switchToKeyboard()
                                                } else {
                                                    openTray(ComposerTrayMode.STICKERS)
                                                }
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .testTag("chat_stickers_button")
                                        ) {
                                            if (isStickersOpen) {
                                                Icon(
                                                    imageVector = Icons.Filled.Keyboard,
                                                    contentDescription = stringResource(R.string.cd_keyboard),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_sticker_smiley),
                                                    contentDescription = stringResource(R.string.cd_stickers),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        BasicTextField(
                                            state = textFieldState,
                                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .weight(1f)
                                                .focusRequester(inputFocusRequester)
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused && activeTrayMode != ComposerTrayMode.NONE) {
                                                        switchToKeyboard()
                                                    }
                                                }
                                                .contentReceiver(receiveContentListener)
                                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                                .testTag("chat_message_input"),
                                            decorator = { innerTextField ->
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    if (draftText.isEmpty()) {
                                                        Text(
                                                            text = stringResource(R.string.message_placeholder),
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )

                                        val isAttachmentsOpen = activeTrayMode == ComposerTrayMode.ATTACHMENTS
                                        val attachRotation by animateFloatAsState(
                                            targetValue = if (isAttachmentsOpen) 45f else 0f,
                                            animationSpec = tween(durationMillis = 200),
                                            label = "attach_icon_rotation"
                                        )
                                        IconButton(
                                            onClick = {
                                                if (isAttachmentsOpen) {
                                                    dismissTray()
                                                } else {
                                                    openTray(ComposerTrayMode.ATTACHMENTS)
                                                }
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .testTag("chat_attach_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Add,
                                                contentDescription = stringResource(
                                                    if (isAttachmentsOpen) R.string.close_btn else R.string.cd_attach_file
                                                ),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .size(23.dp)
                                                    .rotate(attachRotation)
                                            )
                                        }
                                    }
                                }
                            }

                            // Standalone floating circular Send / Voice Mic button
                            if (draftText.isNotBlank()) {
                                Surface(
                                    onClick = {
                                        val toSend = draftText
                                        val replyTarget = replyingToMessage
                                        textFieldState.clearText()
                                        replyingToId = null
                                        markSendingAndScroll()
                                        onSendText(toSend, replyTarget)
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("chat_send_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.cd_send_message),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            onStartVoiceRecording()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("chat_voice_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Mic,
                                            contentDescription = stringResource(R.string.cd_record_voice),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomSpaceDp + navBarBottomDp)
                ) {
                    val renderTrayMode = when {
                        activeTrayMode != ComposerTrayMode.NONE -> activeTrayMode
                        overlayTrayForIme != null -> overlayTrayForIme!!
                        displayedTrayDp > 2.dp -> lastVisibleTrayMode
                        else -> ComposerTrayMode.NONE
                    }

                    if (renderTrayMode != ComposerTrayMode.NONE) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = navBarBottomDp)
                            ) {
                                when (renderTrayMode) {
                                    ComposerTrayMode.STICKERS -> StickersTrayContent(
                                        favoriteStickers = favoriteStickers,
                                        onSendFavoriteSticker = { sticker ->
                                            val replyTarget = replyingToMessage
                                            replyingToId = null
                                            markSendingAndScroll()
                                            onSendFavoriteSticker(sticker, replyTarget)
                                        },
                                        onRemoveFavoriteSticker = onRemoveFavoriteSticker
                                    )

                                    ComposerTrayMode.ATTACHMENTS -> AttachmentsMenuTray(
                                        selfDestructSeconds = peer.selfDestructSeconds,
                                        selfDestructLabel = SelfDestructOption.fromSeconds(peer.selfDestructSeconds).localizedLabel(),
                                        onDismiss = { dismissTray() },
                                        onSendPhotoUri = { uri ->
                                            val replyTarget = replyingToMessage
                                            replyingToId = null
                                            markSendingAndScroll()
                                            onSendPhotoUri(uri, replyTarget)
                                        },
                                        onSendVideoUri = { uri ->
                                            val replyTarget = replyingToMessage
                                            replyingToId = null
                                            markSendingAndScroll()
                                            onSendVideoUri(uri, replyTarget)
                                        },
                                        onSendDocumentUri = { uri ->
                                            val replyTarget = replyingToMessage
                                            replyingToId = null
                                            markSendingAndScroll()
                                            onSendDocumentUri(uri, replyTarget)
                                        },
                                        onOpenSelfDestruct = { showSelfDestructDialog = true },
                                        onNotify = onNotify
                                    )

                                    ComposerTrayMode.NONE -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actionDialogMessage?.let { msg ->
        val isFavorited by produceState(initialValue = false, msg.messageId, favoriteStickers) {
            value = if (msg.type == MessageType.STICKER.name) {
                onCheckStickerFavorited(msg)
            } else {
                false
            }
        }
        MessageActionsReactionDialog(
            message = msg,
            isStickerFavorited = isFavorited,
            onToggleFavoriteSticker = if (msg.type == MessageType.STICKER.name) {
                { onToggleFavoriteSticker(msg) }
            } else {
                null
            },
            onDismiss = { actionDialogMessage = null },
            onSelectEmoji = { emoji -> onToggleReaction(msg.messageId, emoji) },
            onReply = { replyingToId = msg.messageId },
            onCopy = {
                clipboard.setPrimaryClip(ClipData.newPlainText("Chatso", msg.textContent))
                onNotify(copiedMsg)
            },
            onDelete = { onDeleteMessage(msg.messageId) }
        )
    }

    if (showSelfDestructDialog) {
        AlertDialog(
            onDismissRequest = { showSelfDestructDialog = false },
            title = { Text(stringResource(R.string.self_destruct_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.self_destruct_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SelfDestructOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetSelfDestruct(option.seconds)
                                    showSelfDestructDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = peer.selfDestructSeconds == option.seconds,
                                onClick = {
                                    onSetSelfDestruct(option.seconds)
                                    showSelfDestructDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = option.localizedLabel(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSelfDestructDialog = false }) {
                    Text(stringResource(R.string.close_btn))
                }
            }
        )
    }

    if (showSafetyDialog) {
        val safetyNumber = remember(identity.publicKeyBase64, peer.publicKeyBase64) {
            CryptoEngine.computeSafetyNumber(identity.publicKeyBase64, peer.publicKeyBase64)
        }
        AlertDialog(
            onDismissRequest = { showSafetyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.safety_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.safety_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = safetyNumber,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Text(
                        text = "${stringResource(R.string.safety_pubkey_label)}\n${peer.fingerprint}",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val mutualStatusText = when {
                        peer.isMutuallyVerified -> stringResource(R.string.safety_mutual_verified)
                        peer.isVerified && !peer.peerVerifiedMe -> stringResource(R.string.safety_waiting_peer)
                        !peer.isVerified && peer.peerVerifiedMe -> stringResource(R.string.safety_peer_verified_you)
                        else -> null
                    }
                    if (mutualStatusText != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (peer.isMutuallyVerified) {
                                OnlineEmerald.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = mutualStatusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (peer.isMutuallyVerified) {
                                    OnlineEmerald
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onVerifyPeerKey(!peer.isVerified)
                        showSafetyDialog = false
                    }
                ) {
                    Text(
                        text = if (peer.isVerified) {
                            stringResource(R.string.unmark_verified)
                        } else {
                            stringResource(R.string.mark_verified)
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafetyDialog = false }) {
                    Text(stringResource(R.string.close_btn))
                }
            }
        )
    }

    viewerPayload?.let { payload ->
        when (payload.kind) {
            FullscreenViewerTarget.IMAGE -> FullScreenImageViewerDialog(
                imagePath = payload.path,
                fileName = payload.fileName,
                onDismiss = { viewerPayload = null },
                onOpenExternal = { onOpenAttachment(payload.path, "image/*") }
            )
            FullscreenViewerTarget.VIDEO -> FullScreenVideoPlayerDialog(
                videoPath = payload.path,
                fileName = payload.fileName,
                onDismiss = { viewerPayload = null },
                onOpenExternal = { onOpenAttachment(payload.path, "video/*") }
            )
        }
    }
}
