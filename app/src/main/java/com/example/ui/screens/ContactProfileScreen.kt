package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.data.SelfDestructOption
import com.example.ui.components.ChatsoAvatar
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactProfileScreen(
    peer: PeerEntity,
    presence: PeerPresence,
    selfDestructLabel: String,
    bridgePeerName: String? = null,
    onBack: () -> Unit,
    onViewAvatar: (String) -> Unit,
    onOpenSafetyNumber: () -> Unit,
    onNotify: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = rememberClipboard()
    val copiedMsg = stringResource(R.string.msg_copied)
    val avatarFilePath = peer.avatarPath
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> File(path).takeIf { it.exists() }?.absolutePath }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.navigationBars,
        modifier = Modifier
            .fillMaxSize()
            .testTag("contact_profile_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.contact_profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("contact_profile_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .then(
                            if (avatarFilePath != null) {
                                Modifier
                                    .clickable { onViewAvatar(avatarFilePath) }
                                    .testTag("contact_profile_avatar_button")
                            } else {
                                Modifier.testTag("contact_profile_avatar_placeholder")
                            }
                        )
                ) {
                    ChatsoAvatar(
                        name = peer.username,
                        avatarModel = avatarFilePath,
                        size = 140.dp,
                        presence = presence,
                        showPresenceDot = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.username,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (peer.isMutuallyVerified) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = stringResource(R.string.contact_verified_short),
                            tint = OnlineEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val (statusText, statusColor) = when (presence) {
                        PeerPresence.ONLINE -> stringResource(R.string.status_online) to OnlineEmerald
                        PeerPresence.BACKGROUND -> stringResource(R.string.status_background) to WarningAmber
                        PeerPresence.OFFLINE -> stringResource(R.string.status_offline_tap) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    if (peer.viaBridgePeerId.isNotBlank()) {
                        val bridgeLabel = if (!bridgePeerName.isNullOrBlank()) {
                            "${stringResource(R.string.contact_via_bridge)} ($bridgePeerName)"
                        } else {
                            stringResource(R.string.contact_via_bridge)
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = bridgeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    if (peer.irohNodeId.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("contact_profile_iroh_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.iroh_via_relay_badge),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Card 1: Identity & E2EE Verification
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp)) {
                        ContactProfileInfoRow(
                            icon = Icons.Filled.Fingerprint,
                            label = stringResource(R.string.peer_id_label),
                            value = peer.peerId,
                            monospace = true,
                            onCopy = {
                                clipboard.copyText("Peer ID", peer.peerId)
                                onNotify(copiedMsg)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ContactProfileInfoRow(
                            icon = Icons.Filled.Key,
                            label = stringResource(R.string.safety_pubkey_label),
                            value = peer.fingerprint,
                            monospace = true,
                            onCopy = {
                                clipboard.copyText("Fingerprint", peer.fingerprint)
                                onNotify(copiedMsg)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ContactProfileInfoRow(
                            icon = Icons.Filled.Shield,
                            label = stringResource(R.string.contact_verification_label),
                            value = if (peer.isMutuallyVerified) {
                                stringResource(R.string.contact_verified_short)
                            } else {
                                stringResource(R.string.contact_unverified_short)
                            },
                            valueColor = if (peer.isMutuallyVerified) OnlineEmerald else MaterialTheme.colorScheme.onSurface,
                            onClick = onOpenSafetyNumber,
                            showChevron = true,
                            rowTestTag = "contact_profile_safety_row"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: Network & Privacy Settings
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp)) {
                        val endpointText = when {
                            peer.lastIp.isNotBlank() -> "${peer.lastIp}:${peer.lastPort}"
                            peer.viaBridgePeerId.isNotBlank() -> if (!bridgePeerName.isNullOrBlank()) {
                                "${stringResource(R.string.contact_via_bridge)} ($bridgePeerName)"
                            } else {
                                stringResource(R.string.contact_via_bridge)
                            }
                            peer.irohNodeId.isNotBlank() -> stringResource(R.string.contact_via_iroh)
                            else -> "—"
                        }
                        ContactProfileInfoRow(
                            icon = Icons.Filled.Lan,
                            label = stringResource(R.string.contact_network_label),
                            value = endpointText,
                            monospace = true
                        )
                        if (peer.irohNodeId.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            val irohDisplayValue = if (peer.irohRelayUrl.isNotBlank()) {
                                "${peer.irohNodeId}\n${peer.irohRelayUrl}"
                            } else {
                                peer.irohNodeId
                            }
                            val irohCopyValue = if (peer.irohRelayUrl.isNotBlank()) {
                                "${peer.irohNodeId}@${peer.irohRelayUrl}"
                            } else {
                                peer.irohNodeId
                            }
                            ContactProfileInfoRow(
                                icon = Icons.Filled.Public,
                                label = stringResource(R.string.contact_iroh_id_label),
                                value = irohDisplayValue,
                                monospace = true,
                                onCopy = {
                                    clipboard.copyText("Iroh Endpoint ID", irohCopyValue)
                                    onNotify(copiedMsg)
                                },
                                rowTestTag = "contact_profile_iroh_row"
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ContactProfileInfoRow(
                            icon = Icons.Filled.Timer,
                            label = stringResource(R.string.contact_self_destruct_label),
                            value = if (peer.selfDestructSeconds > 0) {
                                selfDestructLabel
                            } else {
                                SelfDestructOption.OFF.let { stringResource(R.string.timer_off) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ContactProfileInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    showChevron: Boolean = false,
    rowTestTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontFamily = if (monospace) JetBrainsMonoFontFamily else null,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onCopy != null) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy_btn),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
