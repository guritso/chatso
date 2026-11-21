package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.PeerEntity
import com.example.data.PeerPresence
import com.example.data.SelfDestructOption
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import java.io.File

@Composable
fun ContactProfileDialog(
    peer: PeerEntity,
    presence: PeerPresence,
    selfDestructLabel: String,
    bridgePeerName: String? = null,
    onDismiss: () -> Unit,
    onViewAvatar: (String) -> Unit,
    onOpenSafetyNumber: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val copiedMsg = stringResource(R.string.msg_copied)
    val avatarFilePath = peer.avatarPath
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> File(path).takeIf { it.exists() }?.absolutePath }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .testTag("contact_profile_dialog")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.contact_profile_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
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
                        size = 108.dp,
                        presence = presence,
                        showPresenceDot = true
                    )
                }

                if (avatarFilePath != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.contact_tap_photo_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.username,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (peer.isMutuallyVerified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = stringResource(R.string.contact_verified_short),
                            tint = OnlineEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = bridgeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                ContactInfoRow(
                    icon = Icons.Filled.Fingerprint,
                    label = stringResource(R.string.peer_id_label),
                    value = peer.peerId,
                    monospace = true,
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Peer ID", peer.peerId))
                        onNotify(copiedMsg)
                    }
                )

                ContactInfoRow(
                    icon = Icons.Filled.Key,
                    label = stringResource(R.string.safety_pubkey_label),
                    value = peer.fingerprint,
                    monospace = true,
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Fingerprint", peer.fingerprint))
                        onNotify(copiedMsg)
                    }
                )

                val endpointText = if (peer.lastIp.isNotBlank()) {
                    "${peer.lastIp}:${peer.lastPort}"
                } else if (peer.viaBridgePeerId.isNotBlank()) {
                    if (!bridgePeerName.isNullOrBlank()) {
                        "${stringResource(R.string.contact_via_bridge)} ($bridgePeerName)"
                    } else {
                        stringResource(R.string.contact_via_bridge)
                    }
                } else {
                    "—"
                }
                ContactInfoRow(
                    icon = Icons.Filled.Lan,
                    label = stringResource(R.string.contact_network_label),
                    value = endpointText,
                    monospace = true
                )

                ContactInfoRow(
                    icon = Icons.Filled.Shield,
                    label = stringResource(R.string.contact_verification_label),
                    value = if (peer.isMutuallyVerified) {
                        stringResource(R.string.contact_verified_short)
                    } else {
                        stringResource(R.string.contact_unverified_short)
                    },
                    valueColor = if (peer.isMutuallyVerified) OnlineEmerald else MaterialTheme.colorScheme.onSurface,
                    onClick = onOpenSafetyNumber
                )

                ContactInfoRow(
                    icon = Icons.Filled.Timer,
                    label = stringResource(R.string.contact_self_destruct_label),
                    value = if (peer.selfDestructSeconds > 0) {
                        selfDestructLabel
                    } else {
                        SelfDestructOption.OFF.let { stringResource(R.string.timer_off) }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("contact_profile_close_button")
                    ) {
                        Text(stringResource(R.string.close_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontFamily = if (monospace) JetBrainsMonoFontFamily else null,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onCopy != null) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy_btn),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
