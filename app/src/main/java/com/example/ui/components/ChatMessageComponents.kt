package com.example.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.data.ActiveTransferInfo
import com.example.data.DeliveryStatus
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.ReactionCodec
import com.example.data.ReplySenderTags
import com.example.media.VoicePlaybackState
import com.example.network.NetworkUtils
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatMessageBubbleItem(
    message: MessageEntity,
    peerUsername: String,
    nowClockFlow: StateFlow<Long>,
    voicePlaybackState: VoicePlaybackState,
    onToggleVoice: (String, String) -> Unit,
    onOpenImageFullscreen: (path: String, fileName: String?) -> Unit,
    onOpenVideoFullscreen: (path: String, fileName: String?) -> Unit,
    onOpenAttachment: (String, String?) -> Unit,
    onSwipeReply: (MessageEntity) -> Unit,
    onLongPressMessage: (MessageEntity) -> Unit,
    onReactionChipClick: (messageId: String, emoji: String) -> Unit,
    onQuoteClick: (replyToMessageId: String) -> Unit
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
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (isOutgoing) 16.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 16.dp
        )
    }
    val formattedTimestamp = remember(message.createdAt) {
        NetworkUtils.formatTimestamp(message.createdAt)
    }
    val mediaFileReady = remember(message.type, message.mediaLocalPath, message.transferProgress) {
        (message.type == MessageType.IMAGE.name || message.type == MessageType.VIDEO.name) &&
            !message.mediaLocalPath.isNullOrBlank() &&
            message.transferProgress >= 100 &&
            File(message.mediaLocalPath).exists()
    }

    val density = LocalDensity.current
    val maxSwipePx = remember(density) { with(density) { 64.dp.toPx() } }
    val triggerSwipePx = remember(density) { with(density) { 42.dp.toPx() } }
    val dragOffset = remember(message.messageId) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val reactionChips = remember(message.reactionsJson) {
        ReactionCodec.groupedChips(message.reactionsJson)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(message.messageId) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        val next = (dragOffset.value + dragAmount).coerceIn(0f, maxSwipePx)
                        scope.launch { dragOffset.snapTo(next) }
                    },
                    onDragEnd = {
                        val reached = dragOffset.value >= triggerSwipePx
                        scope.launch { dragOffset.animateTo(0f, spring()) }
                        if (reached) onSwipeReply(message)
                    },
                    onDragCancel = {
                        scope.launch { dragOffset.animateTo(0f, spring()) }
                    }
                )
            }
    ) {
        if (dragOffset.value > 6f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .alpha((dragOffset.value / triggerSwipePx).coerceIn(0f, 1f))
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) },
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(min = 68.dp, max = 276.dp)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onLongPressMessage(message) }
                    )
                    .testTag("message_bubble_${message.messageId}")
            ) {
                val outerPad = if (mediaFileReady) {
                    PaddingValues(2.dp)
                } else {
                    PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                }
                Column(modifier = Modifier.padding(outerPad)) {
                    if (!message.replyToMessageId.isNullOrBlank() && !message.replyToPreview.isNullOrBlank()) {
                        val replyAuthorName = when (message.replyToSenderTag) {
                            ReplySenderTags.ME -> stringResource(R.string.reply_you)
                            else -> peerUsername
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (isOutgoing) 0.45f else 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = if (mediaFileReady) 4.dp else 0.dp,
                                    end = if (mediaFileReady) 4.dp else 0.dp,
                                    top = if (mediaFileReady) 4.dp else 0.dp,
                                    bottom = 4.dp
                                )
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onQuoteClick(message.replyToMessageId) }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(26.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = replyAuthorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = message.replyToPreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    when (message.type) {
                        MessageType.IMAGE.name -> {
                            if (mediaFileReady && message.mediaLocalPath != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .combinedClickable(
                                            onClick = {
                                                onOpenImageFullscreen(message.mediaLocalPath, message.fileName)
                                            },
                                            onLongClick = { onLongPressMessage(message) }
                                        )
                                ) {
                                    AsyncImage(
                                        model = File(message.mediaLocalPath),
                                        contentDescription = message.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    MediaTimestampOverlayPill(
                                        message = message,
                                        formattedTimestamp = formattedTimestamp,
                                        isOutgoing = isOutgoing,
                                        nowClockFlow = nowClockFlow,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                    )
                                }
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

                        MessageType.VIDEO.name -> {
                            if (mediaFileReady && message.mediaLocalPath != null) {
                                val thumb by produceState<Bitmap?>(null, message.mediaLocalPath) {
                                    value = withContext(Dispatchers.IO) {
                                        val retriever = MediaMetadataRetriever()
                                        try {
                                            retriever.setDataSource(message.mediaLocalPath)
                                            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                        } catch (_: Exception) {
                                            null
                                        } finally {
                                            runCatching { retriever.release() }
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Black.copy(alpha = 0.85f))
                                        .combinedClickable(
                                            onClick = {
                                                onOpenVideoFullscreen(message.mediaLocalPath, message.fileName)
                                            },
                                            onLongClick = { onLongPressMessage(message) }
                                        )
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb!!.asImageBitmap(),
                                            contentDescription = message.fileName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .align(Alignment.Center)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.58f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = stringResource(R.string.video_label),
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.62f),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Videocam,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            val durLabel = if (message.audioDurationMs > 0L) {
                                                NetworkUtils.formatDurationMs(message.audioDurationMs)
                                            } else {
                                                stringResource(R.string.video_label)
                                            }
                                            Text(
                                                text = durLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    MediaTimestampOverlayPill(
                                        message = message,
                                        formattedTimestamp = formattedTimestamp,
                                        isOutgoing = isOutgoing,
                                        nowClockFlow = nowClockFlow,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                    )
                                }
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
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.voice_message_label),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
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
                                    Spacer(modifier = Modifier.height(3.dp))
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
                                        .size(36.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
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
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        onOpenAttachment(message.mediaLocalPath, message.mimeType)
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.open_file_btn),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        else -> {
                            Text(
                                text = message.textContent,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                                color = contentColor
                            )
                        }
                    }

                    if (!mediaFileReady) {
                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                    modifier = Modifier.size(11.dp)
                                )
                            }

                            Text(
                                text = formattedTimestamp,
                                style = MaterialTheme.typography.labelSmall,
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
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (reactionChips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)
                ) {
                    reactionChips.forEach { chip ->
                        Surface(
                            shape = CircleShape,
                            color = if (chip.byMe) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = if (chip.byMe) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape
                                )
                                .clip(CircleShape)
                                .clickable { onReactionChipClick(message.messageId, chip.emoji) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = chip.emoji, fontSize = 13.sp)
                                if (chip.count > 1) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = chip.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
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

@Composable
private fun MediaTimestampOverlayPill(
    message: MessageEntity,
    formattedTimestamp: String,
    isOutgoing: Boolean,
    nowClockFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.62f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (message.expiresAt != null) {
                SelfDestructCountdownText(
                    expiresAt = message.expiresAt,
                    nowClockFlow = nowClockFlow,
                    tintColor = Color(0xFFFCA5A5)
                )
            } else if (message.selfDestructSeconds > 0) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(11.dp)
                )
            }

            Text(
                text = formattedTimestamp,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
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
                    DeliveryStatus.READ.name -> Color(0xFF5EEAD4)
                    DeliveryStatus.FAILED.name -> DangerCoral
                    else -> Color.White.copy(alpha = 0.9f)
                }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = message.status,
                    tint = iconTint,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun SelfDestructCountdownText(
    expiresAt: Long,
    nowClockFlow: StateFlow<Long>,
    tintColor: Color = DangerCoral
) {
    val nowClockMs by nowClockFlow.collectAsStateWithLifecycle()
    val remainingSeconds = ((expiresAt - nowClockMs) / 1000L).coerceAtLeast(0L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(11.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "${remainingSeconds}s",
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = tintColor
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageActionsReactionDialog(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val (myReaction, _) = remember(message.reactionsJson) {
        ReactionCodec.parse(message.reactionsJson)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReactionCodec.quickEmojis.forEach { emoji ->
                        val isSelected = myReaction == emoji
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable {
                                    onSelectEmoji(emoji)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 22.sp)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onReply()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("message_action_reply")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.reply_action),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (message.type == MessageType.TEXT.name && message.textContent.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onCopy()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("message_action_copy")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.copy_text_action),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            onDismiss()
                            onDelete()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("message_action_delete")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = DangerCoral,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.delete_message_action),
                            color = DangerCoral,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingTransfersOverlay(
    transfers: List<ActiveTransferInfo>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = transfers.isNotEmpty(),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        val avgProgress = if (transfers.isEmpty()) {
            0f
        } else {
            (transfers.sumOf { it.progress }.toFloat() / (transfers.size * 100f)).coerceIn(0.02f, 1f)
        }
        val anyOutgoing = transfers.any { it.isOutgoing }

        Column(horizontalAlignment = Alignment.End) {
            Box(modifier = Modifier.size(54.dp)) {
                Surface(
                    onClick = onToggleExpanded,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                        .testTag("floating_transfers_bubble")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { avgProgress },
                            strokeWidth = 3.5.dp,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Icon(
                            imageVector = if (anyOutgoing) {
                                Icons.Filled.ArrowUpward
                            } else {
                                Icons.Filled.ArrowDownward
                            },
                            contentDescription = stringResource(R.string.transfers_title, transfers.size),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (transfers.size > 1) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = transfers.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .widthIn(min = 220.dp, max = 280.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.transfers_title, transfers.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(
                                onClick = onCollapse,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.close_btn),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        transfers.take(6).forEach { tr ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tr.fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${tr.progress}%",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "${
                                        if (tr.isOutgoing) {
                                            stringResource(R.string.transfer_uploading)
                                        } else {
                                            stringResource(R.string.transfer_downloading)
                                        }
                                    } · ${NetworkUtils.formatBytes(tr.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (tr.progress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
