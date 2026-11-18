package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.crypto.CryptoEngine
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentity
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.data.SelfDestructOption
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecordingState
import com.example.network.NetworkUtils
import com.example.ui.components.ChatsoAvatar
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import java.io.File
import kotlinx.coroutines.flow.StateFlow

@Composable
private fun SelfDestructOption.localizedLabel(): String = when (this) {
    SelfDestructOption.OFF -> stringResource(R.string.timer_off)
    SelfDestructOption.SEC_30 -> stringResource(R.string.timer_30s)
    SelfDestructOption.MIN_5 -> stringResource(R.string.timer_5m)
    SelfDestructOption.HOUR_1 -> stringResource(R.string.timer_1h)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatDetailScreen(
    identity: LocalIdentity,
    peer: PeerEntity,
    messages: List<MessageEntity>,
    isConnected: Boolean,
    isPeerTyping: Boolean,
    nowClockFlow: StateFlow<Long>,
    voiceRecordingState: VoiceRecordingState,
    voicePlaybackState: VoicePlaybackState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onLoadMoreMessages: () -> Unit,
    onSendText: (String) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onSendPhotoUri: (Uri) -> Unit,
    onSendDocumentUri: (Uri) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onSendVoiceRecording: () -> Unit,
    onToggleVoicePlayback: (messageId: String, localPath: String) -> Unit,
    onOpenAttachment: (localPath: String, mimeType: String?) -> Unit,
    onSetSelfDestruct: (Int) -> Unit,
    onVerifyPeerKey: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDeleteConversation: () -> Unit
) {
    val context = LocalContext.current
    var draftText by rememberSaveable(peer.peerId) { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSelfDestructDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // Messages are ordered newest-first (DESC) and LazyColumn uses reverseLayout = true,
    // so index 0 IS the bottom latest message on frame 1 with zero initial scroll lag.
    val newestMessageId = messages.firstOrNull()?.messageId
    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    val shouldLoadMore by remember(messages.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            messages.isNotEmpty() && lastVisible >= messages.size - 15
        }
    }
    LaunchedEffect(shouldLoadMore, messages.size) {
        if (shouldLoadMore) {
            onLoadMoreMessages()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onSendPhotoUri(uri)
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onSendDocumentUri(uri)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoiceRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Minimalist Header Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
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
                    isOnline = isConnected,
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
                        if (peer.isVerified) {
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
                            isConnected -> stringResource(R.string.status_online)
                            else -> stringResource(R.string.status_offline_tap)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isPeerTyping -> MaterialTheme.colorScheme.primary
                            isConnected -> OnlineEmerald
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
                        tint = if (peer.isVerified) {
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

        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imeNestedScroll()
                .testTag("chat_messages_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = messages,
                key = { it.messageId },
                contentType = { it.type }
            ) { message ->
                MessageBubbleItem(
                    message = message,
                    nowClockFlow = nowClockFlow,
                    voicePlaybackState = voicePlaybackState,
                    onToggleVoice = onToggleVoicePlayback,
                    onOpenImageFullscreen = { path -> fullscreenImagePath = path },
                    onOpenAttachment = onOpenAttachment
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            ) {
            if (voiceRecordingState.isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onCancelVoiceRecording,
                        modifier = Modifier.testTag("voice_cancel_button")
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
                                .size(10.dp)
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

                    Button(
                        onClick = onSendVoiceRecording,
                        shape = CircleShape,
                        modifier = Modifier.testTag("voice_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_btn),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.send_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.testTag("chat_attach_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.cd_attach_file),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.send_photo)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.send_file)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Description, contentDescription = null)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    documentPicker.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { next ->
                            draftText = next
                            onTypingChanged(next.isNotBlank())
                        },
                        placeholder = { Text(stringResource(R.string.message_placeholder)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (draftText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val toSend = draftText
                                draftText = ""
                                onSendText(toSend)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("chat_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.cd_send_message),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        IconButton(
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
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .testTag("chat_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.cd_record_voice),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            }
        }
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

    fullscreenImagePath?.let { path ->
        Dialog(onDismissRequest = { fullscreenImagePath = null }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { onOpenAttachment(path, "image/*") }) {
                            Text(stringResource(R.string.open_external_btn), color = Color.White)
                        }
                        IconButton(onClick = { fullscreenImagePath = null }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.close_btn),
                                tint = Color.White
                            )
                        }
                    }
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelfDestructCountdownText(
    expiresAt: Long,
    nowClockFlow: StateFlow<Long>
) {
    val nowClockMs by nowClockFlow.collectAsStateWithLifecycle()
    val remainingSeconds = ((expiresAt - nowClockMs) / 1000L).coerceAtLeast(0L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = DangerCoral,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "${remainingSeconds}s",
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = DangerCoral
        )
    }
}

@Composable
private fun MessageBubbleItem(
    message: MessageEntity,
    nowClockFlow: StateFlow<Long>,
    voicePlaybackState: VoicePlaybackState,
    onToggleVoice: (String, String) -> Unit,
    onOpenImageFullscreen: (String) -> Unit,
    onOpenAttachment: (String, String?) -> Unit
) {
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleShape = remember(isOutgoing) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (isOutgoing) 18.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 18.dp
        )
    }
    val formattedTimestamp = remember(message.createdAt) {
        NetworkUtils.formatTimestamp(message.createdAt)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(min = 96.dp, max = 304.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                when (message.type) {
                    MessageType.IMAGE.name -> {
                        val fileExists = remember(message.mediaLocalPath, message.transferProgress) {
                            !message.mediaLocalPath.isNullOrBlank() &&
                                message.transferProgress >= 100 &&
                                File(message.mediaLocalPath).exists()
                        }
                        if (fileExists && message.mediaLocalPath != null) {
                            AsyncImage(
                                model = File(message.mediaLocalPath),
                                contentDescription = message.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onOpenImageFullscreen(message.mediaLocalPath) }
                            )
                        } else {
                            Text(
                                text = message.textContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                        if (message.transferProgress in 1..99) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { message.transferProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    MessageType.VOICE.name -> {
                        val isThisPlaying = voicePlaybackState.activeMessageId == message.messageId &&
                            voicePlaybackState.isPlaying
                        val progressFraction = if (
                            voicePlaybackState.activeMessageId == message.messageId &&
                            voicePlaybackState.durationMs > 0
                        ) {
                            (voicePlaybackState.positionMs.toFloat() / voicePlaybackState.durationMs.toFloat())
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    val path = message.mediaLocalPath
                                    if (!path.isNullOrBlank() && message.transferProgress >= 100) {
                                        onToggleVoice(message.messageId, path)
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.voice_message_label),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = {
                                        if (message.transferProgress < 100) {
                                            message.transferProgress / 100f
                                        } else {
                                            progressFraction
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${stringResource(R.string.voice_message_label)} · ${NetworkUtils.formatDurationMs(message.audioDurationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    MessageType.FILE.name -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.fileName ?: "File",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = NetworkUtils.formatBytes(message.fileSizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.75f)
                                )
                            }
                        }

                        if (message.transferProgress < 100) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { message.transferProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (!message.mediaLocalPath.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onOpenAttachment(message.mediaLocalPath, message.mimeType)
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.open_file_btn),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = message.textContent,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (message.expiresAt != null) {
                        SelfDestructCountdownText(
                            expiresAt = message.expiresAt,
                            nowClockFlow = nowClockFlow
                        )
                    } else if (message.selfDestructSeconds > 0) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    Text(
                        text = formattedTimestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.68f)
                    )

                    if (isOutgoing) {
                        val statusIcon = when (message.status) {
                            DeliveryStatus.SENDING.name -> Icons.Filled.Schedule
                            DeliveryStatus.SENT.name -> Icons.Filled.Check
                            DeliveryStatus.DELIVERED.name,
                            DeliveryStatus.READ.name -> Icons.Filled.DoneAll
                            else -> Icons.Filled.ErrorOutline
                        }
                        val iconTint = when (message.status) {
                            DeliveryStatus.READ.name -> MaterialTheme.colorScheme.primary
                            DeliveryStatus.FAILED.name -> DangerCoral
                            else -> contentColor.copy(alpha = 0.7f)
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = message.status,
                            tint = iconTint,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}
