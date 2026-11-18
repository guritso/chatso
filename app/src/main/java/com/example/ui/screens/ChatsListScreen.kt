package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LocalIdentity
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.network.NetworkUtils
import com.example.ui.components.ChatsoAvatar
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.StealthIndigo
import com.example.ui.theme.WarningAmber

@Composable
fun ChatsListScreen(
    identity: LocalIdentity,
    peers: List<PeerEntity>,
    connectedPeerIds: Set<String>,
    foregroundPeerIds: Set<String>,
    typingPeerIds: Set<String>,
    selectedPeerId: String?,
    onToggleDiscoverable: (Boolean) -> Unit,
    onOpenPeer: (String) -> Unit,
    onGoToConnect: () -> Unit,
    onQuickTestLocalhost: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredPeers = if (searchQuery.isBlank()) {
        peers
    } else {
        peers.filter {
            it.username.contains(searchQuery, ignoreCase = true) ||
                it.lastIp.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onGoToConnect,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_new_connection")
            ) {
                Icon(
                    imageVector = Icons.Filled.AddComment,
                    contentDescription = stringResource(R.string.new_connection)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // Stable, fixed-height classic toolbar so toggling visibility never shifts height
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            ChatsoAvatar(
                                name = identity.username,
                                avatarModel = identity.avatarPath,
                                size = 36.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Compact, fixed-height pill that never wraps or alters toolbar height
                        Surface(
                            shape = CircleShape,
                            color = if (identity.isDiscoverable) {
                                OnlineEmerald.copy(alpha = 0.14f)
                            } else {
                                StealthIndigo.copy(alpha = 0.16f)
                            },
                            modifier = Modifier
                                .height(34.dp)
                                .clip(CircleShape)
                                .clickable { onToggleDiscoverable(!identity.isDiscoverable) }
                                .testTag("header_visibility_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (identity.isDiscoverable) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = stringResource(R.string.cd_toggle_visibility),
                                    tint = if (identity.isDiscoverable) OnlineEmerald else StealthIndigo,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (identity.isDiscoverable) {
                                        stringResource(R.string.status_visible)
                                    } else {
                                        stringResource(R.string.status_invisible)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (identity.isDiscoverable) OnlineEmerald else StealthIndigo,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    if (peers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search_chats_hint),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null
                                )
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chats_search_input")
                        )
                    }
                }
            }

            if (peers.isEmpty()) {
                EmptyChatsView(
                    onGoToConnect = onGoToConnect,
                    onQuickTestLocalhost = onQuickTestLocalhost
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chats_list"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPeers, key = { it.peerId }) { peer ->
                        val isConnected = connectedPeerIds.contains(peer.peerId)
                        val presence = when {
                            !isConnected -> PeerPresence.OFFLINE
                            foregroundPeerIds.contains(peer.peerId) -> PeerPresence.ONLINE
                            else -> PeerPresence.BACKGROUND
                        }
                        val isTyping = typingPeerIds.contains(peer.peerId)
                        val isSelected = selectedPeerId == peer.peerId

                        PeerConversationCard(
                            peer = peer,
                            presence = presence,
                            isTyping = isTyping,
                            isSelected = isSelected,
                            onClick = { onOpenPeer(peer.peerId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerConversationCard(
    peer: PeerEntity,
    presence: PeerPresence,
    isTyping: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("peer_card_${peer.peerId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatsoAvatar(
                name = peer.username,
                avatarModel = peer.avatarPath,
                size = 50.dp,
                presence = presence,
                showPresenceDot = true
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = peer.username,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (peer.keyChangedWarning) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = stringResource(R.string.key_changed_warning),
                                tint = WarningAmber,
                                modifier = Modifier.size(16.dp)
                            )
                        } else if (peer.isMutuallyVerified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = stringResource(R.string.safety_title),
                                tint = OnlineEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (peer.selfDestructSeconds > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = stringResource(R.string.self_destruct_title),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Text(
                        text = NetworkUtils.formatTimestamp(peer.lastActivityAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (peer.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isTyping) {
                            stringResource(R.string.status_typing)
                        } else {
                            peer.lastMessagePreview
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (peer.unreadCount > 0 || isTyping) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (isTyping) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (peer.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peer.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatsView(
    onGoToConnect: () -> Unit,
    onQuickTestLocalhost: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.empty_chats_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.empty_chats_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = onGoToConnect,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("empty_connect_button")
            ) {
                Text(
                    text = stringResource(R.string.connect_cta),
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onQuickTestLocalhost,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("empty_localhost_test_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.test_localhost_cta),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
