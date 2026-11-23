package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.network.NetworkUtils
import com.example.ui.PendingIncomingShare
import com.example.ui.StagedSharedFile
import com.example.ui.components.ChatsoAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    share: PendingIncomingShare,
    peers: List<PeerEntity>,
    connectedPeerIds: Set<String>,
    foregroundPeerIds: Set<String>,
    onSend: (selectedPeerIds: Set<String>, messageText: String) -> Unit,
    onImportStickerPack: (StagedSharedFile, String) -> Unit = { _, _ -> },
    onGoToConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var forcePickerMode by rememberSaveable(share.id) { mutableStateOf(false) }
    var selectedPeerIds by rememberSaveable(share.id) { mutableStateOf(emptySet<String>()) }
    var messageText by rememberSaveable(share.id) { mutableStateOf(share.sharedText ?: "") }
    var searchQuery by rememberSaveable(share.id) { mutableStateOf("") }

    val directPeer = if (!forcePickerMode && !share.directTargetPeerId.isNullOrBlank()) {
        peers.firstOrNull { it.peerId == share.directTargetPeerId }
    } else {
        null
    }

    val orderedPeers = peers.sortedByDescending { it.lastActivityAt }
    val filteredPeers = if (searchQuery.isBlank()) {
        orderedPeers
    } else {
        orderedPeers.filter {
            it.username.contains(searchQuery, ignoreCase = true) ||
                it.lastIp.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!share.isSending) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("share_target_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (directPeer != null) {
                        stringResource(R.string.share_send_to_contact, directPeer.username)
                    } else {
                        stringResource(R.string.share_to_chatso_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    enabled = !share.isSending,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("share_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(android.R.string.cancel)
                    )
                }
            }

            if (share.isLoadingMedia || share.sharedFiles.isNotEmpty()) {
                SharedAttachmentsPreview(
                    isLoadingMedia = share.isLoadingMedia,
                    sharedFiles = share.sharedFiles
                )
            }

            val packCandidate = share.sharedFiles.firstOrNull { it.detectedStickerPack != null }
            val detectedPack = packCandidate?.detectedStickerPack
            if (packCandidate != null && detectedPack != null) {
                var customPackName by rememberSaveable(share.id, packCandidate.file.absolutePath) {
                    mutableStateOf(detectedPack.suggestedName)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_detected_sticker_pack_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sticker_smiley),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.sticker_pack_detected_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.sticker_pack_detected_subtitle,
                                        detectedPack.author.ifBlank { packCandidate.fileName },
                                        detectedPack.stickerCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (detectedPack.previewStickers.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(detectedPack.previewStickers.take(8)) { bytes ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(54.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(bytes)
                                                    .build(),
                                                contentDescription = stringResource(R.string.sticker_label),
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = customPackName,
                            onValueChange = { customPackName = it },
                            singleLine = true,
                            enabled = !share.isSending,
                            label = { Text(stringResource(R.string.sticker_pack_name_label)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("share_import_pack_name_input")
                        )

                        Button(
                            onClick = { onImportStickerPack(packCandidate, customPackName) },
                            enabled = !share.isSending && customPackName.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("share_import_sticker_pack_button")
                        ) {
                            Text(stringResource(R.string.sticker_pack_import_confirm, detectedPack.stickerCount))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                enabled = !share.isSending,
                label = { Text(stringResource(R.string.share_message_optional_label)) },
                placeholder = { Text(stringResource(R.string.share_message_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share_message_input")
            )

            val hasPayload = share.sharedFiles.isNotEmpty() || messageText.isNotBlank()

            if (directPeer != null) {
                val presence = resolvePresence(directPeer.peerId, connectedPeerIds, foregroundPeerIds)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChatsoAvatar(
                            name = directPeer.username,
                            avatarModel = directPeer.avatarPath,
                            size = 44.dp,
                            presence = presence,
                            showPresenceDot = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = directPeer.username,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (directPeer.viaBridgePeerId.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Hub,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.contact_via_bridge),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                selectedPeerIds = setOf(directPeer.peerId)
                                forcePickerMode = true
                            },
                            enabled = !share.isSending,
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .testTag("share_change_contact_btn")
                        ) {
                            Text(stringResource(R.string.share_change_contact_btn))
                        }
                    }
                }

                Button(
                    onClick = { onSend(setOf(directPeer.peerId), messageText) },
                    enabled = !share.isLoadingMedia && !share.isSending && hasPayload,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("share_confirm_send_button")
                ) {
                    if (share.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.share_send_to_contact, directPeer.username),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (peers.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.share_no_contacts_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.share_no_contacts_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onGoToConnect,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("share_go_to_connect_button")
                        ) {
                            Text(stringResource(R.string.share_go_to_connect_btn))
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.share_select_contacts_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (peers.size > 4 || searchQuery.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_search_contacts_input")
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredPeers, key = { it.peerId }) { peer ->
                        val isSelected = selectedPeerIds.contains(peer.peerId)
                        val presence = resolvePresence(peer.peerId, connectedPeerIds, foregroundPeerIds)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            },
                            border = if (isSelected) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !share.isSending) {
                                    selectedPeerIds = if (isSelected) {
                                        selectedPeerIds - peer.peerId
                                    } else {
                                        selectedPeerIds + peer.peerId
                                    }
                                }
                                .testTag("share_peer_row_${peer.peerId}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ChatsoAvatar(
                                    name = peer.username,
                                    avatarModel = peer.avatarPath,
                                    size = 42.dp,
                                    presence = presence,
                                    showPresenceDot = true
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = peer.username,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (peer.viaBridgePeerId.isNotBlank()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Hub,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.contact_via_bridge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else if (peer.lastMessagePreview.isNotBlank()) {
                                        Text(
                                            text = peer.lastMessagePreview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { onSend(selectedPeerIds, messageText) },
                    enabled = selectedPeerIds.isNotEmpty() && !share.isLoadingMedia && !share.isSending && hasPayload,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("share_send_selected_button")
                ) {
                    if (share.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.share_send_count_btn, selectedPeerIds.size))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SharedAttachmentsPreview(
    isLoadingMedia: Boolean,
    sharedFiles: List<StagedSharedFile>
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLoadingMedia) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.share_loading_media),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val totalBytes = sharedFiles.sumOf { it.sizeBytes }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (sharedFiles.size == 1) {
                            stringResource(R.string.share_files_count_single)
                        } else {
                            stringResource(R.string.share_files_count_plural, sharedFiles.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "· ${NetworkUtils.formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(sharedFiles, key = { it.file.absolutePath }) { item ->
                        when (item.messageType) {
                            MessageType.IMAGE -> {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(item.file)
                                            .size(220)
                                            .build(),
                                        contentDescription = item.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            MessageType.VIDEO -> {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(76.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.VideoFile,
                                            contentDescription = item.fileName,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = item.fileName,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            else -> {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .widthIn(min = 136.dp, max = 180.dp)
                                        .height(76.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Description,
                                            contentDescription = item.fileName,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = item.fileName,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
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
}

private fun resolvePresence(
    peerId: String,
    connectedPeerIds: Set<String>,
    foregroundPeerIds: Set<String>
): PeerPresence = when {
    !connectedPeerIds.contains(peerId) -> PeerPresence.OFFLINE
    foregroundPeerIds.contains(peerId) -> PeerPresence.ONLINE
    else -> PeerPresence.BACKGROUND
}
