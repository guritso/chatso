package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ScheduleSend
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BridgeTelemetry
import com.example.data.ChatRepository
import com.example.data.LocalIdentity
import com.example.data.NetworkEndpointInfo
import com.example.data.PeerEntity
import com.example.network.NetworkUtils
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevModeScreen(
    identity: LocalIdentity,
    peers: List<PeerEntity>,
    endpoints: List<NetworkEndpointInfo>,
    boundPort: Int,
    serverRunning: Boolean,
    totalMessageCount: Int,
    devOnlineFakePeers: Set<String>,
    devAutoReplyEnabled: Boolean,
    scheduledDelayRemainingSec: Int?,
    bridgeTelemetry: BridgeTelemetry = BridgeTelemetry(),
    onBack: () -> Unit,
    onCreateFakePeer: (String, onCreated: (PeerEntity) -> Unit) -> Unit,
    onSendFakeIncomingText: (peerId: String, text: String, delaySeconds: Int) -> Unit,
    onSendFakeUnreadBurst: (peerId: String, baseText: String, count: Int) -> Unit,
    onSendFakeMedia: (peerId: String, asSticker: Boolean) -> Unit,
    onSimulateTyping: (peerId: String) -> Unit,
    onSimulateReaction: (peerId: String) -> Unit,
    onTriggerKeyWarning: (PeerEntity) -> Unit,
    onTogglePeerSimulatedOnline: (peerId: String, online: Boolean) -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onOpenPeerChat: (peerId: String) -> Unit,
    onClearFakePeers: () -> Unit,
    onNotify: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val copiedMsg = stringResource(R.string.msg_copied)
    val defaultFakeMsg = stringResource(R.string.dev_fake_msg_default)

    var fakeNameInput by rememberSaveable { mutableStateOf("") }
    var fakeMsgInput by rememberSaveable { mutableStateOf(defaultFakeMsg) }
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }

    val fakePeers = remember(peers) {
        peers.filter { it.peerId.startsWith(ChatRepository.DEV_FAKE_PEER_PREFIX) }
    }

    LaunchedEffect(fakePeers) {
        if (selectedPeerId == null || fakePeers.none { it.peerId == selectedPeerId }) {
            selectedPeerId = fakePeers.firstOrNull()?.peerId
        }
    }

    val selectedPeer = fakePeers.firstOrNull { it.peerId == selectedPeerId }
    val fakePeerCount = fakePeers.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("dev_mode_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("dev_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dev_tools_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 1. Fake User Generator & Target Selector
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dev_fake_peer_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = fakeNameInput,
                    onValueChange = { fakeNameInput = it.take(32) },
                    label = { Text(stringResource(R.string.dev_fake_name_label)) },
                    placeholder = { Text(stringResource(R.string.dev_fake_name_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dev_fake_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val name = fakeNameInput
                        fakeNameInput = ""
                        onCreateFakePeer(name) { created ->
                            selectedPeerId = created.peerId
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dev_create_fake_peer_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_create_fake_peer_btn))
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.dev_target_peer_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (fakePeers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dev_no_peers_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        fakePeers.forEach { peer ->
                            FilterChip(
                                selected = peer.peerId == selectedPeerId,
                                onClick = { selectedPeerId = peer.peerId },
                                label = {
                                    Text(
                                        text = "${peer.username} · FAKE",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("dev_target_chip_${peer.peerId}")
                            )
                        }
                    }
                }
            }
        }

        // 2. Incoming Message Simulator (Foreground & Background 5s Delayed)
        if (selectedPeer != null) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_incoming_sim_section),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        OutlinedButton(
                            onClick = { onOpenPeerChat(selectedPeer.peerId) },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("dev_open_target_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.open_file_btn),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = fakeMsgInput,
                        onValueChange = { fakeMsgInput = it },
                        label = { Text(stringResource(R.string.dev_fake_msg_label)) },
                        shape = MaterialTheme.shapes.medium,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dev_fake_msg_input")
                    )

                    if (scheduledDelayRemainingSec != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.dev_scheduled_countdown,
                                    scheduledDelayRemainingSec
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onSendFakeIncomingText(selectedPeer.peerId, fakeMsgInput, 0)
                            },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dev_send_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.dev_send_now_btn),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onSendFakeIncomingText(selectedPeer.peerId, fakeMsgInput, 5)
                            },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dev_send_delayed_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ScheduleSend,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.dev_send_delayed_btn),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            onSendFakeUnreadBurst(selectedPeer.peerId, fakeMsgInput, 5)
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dev_unread_burst_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MarkChatUnread,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dev_unread_burst_btn))
                    }
                }
            }

            // 3. Events, Presence & Media Simulation
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.dev_events_section),
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (selectedPeer.peerId.startsWith(ChatRepository.DEV_FAKE_PEER_PREFIX)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val isSimOnline = devOnlineFakePeers.contains(selectedPeer.peerId)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dev_sim_online_toggle),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isSimOnline,
                                onCheckedChange = {
                                    onTogglePeerSimulatedOnline(selectedPeer.peerId, it)
                                },
                                modifier = Modifier.testTag("dev_sim_online_switch")
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dev_auto_reply_toggle),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.dev_auto_reply_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = devAutoReplyEnabled,
                                onCheckedChange = onToggleAutoReply,
                                modifier = Modifier.testTag("dev_auto_reply_switch")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSendFakeMedia(selectedPeer.peerId, false) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("dev_sim_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_sim_photo_btn))
                        }

                        OutlinedButton(
                            onClick = { onSendFakeMedia(selectedPeer.peerId, true) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("dev_sim_sticker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SentimentSatisfied,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_sim_sticker_btn))
                        }

                        OutlinedButton(
                            onClick = { onSimulateTyping(selectedPeer.peerId) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("dev_sim_typing_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_sim_typing_btn))
                        }

                        OutlinedButton(
                            onClick = { onSimulateReaction(selectedPeer.peerId) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("dev_sim_reaction_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_sim_reaction_btn))
                        }

                        OutlinedButton(
                            onClick = { onTriggerKeyWarning(selectedPeer) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("dev_key_warning_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dev_key_warning_btn))
                        }
                    }
                }
            }
        }

        // 4. Runtime Diagnostics & Cleanup Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dev_diagnostics_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                DiagnosticRow(
                    label = stringResource(R.string.dev_diag_peer_id),
                    value = identity.peerId.take(16) + "…",
                    onCopy = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Peer ID", identity.peerId))
                        onNotify(copiedMsg)
                    }
                )
                DiagnosticRow(
                    label = stringResource(R.string.dev_diag_server_port),
                    value = ":$boundPort · ${if (serverRunning) "LISTENING" else "STOPPED"}",
                    valueColor = if (serverRunning) OnlineEmerald else DangerCoral
                )
                DiagnosticRow(
                    label = stringResource(R.string.dev_diag_peers_count),
                    value = "${peers.size} / $fakePeerCount"
                )
                DiagnosticRow(
                    label = stringResource(R.string.dev_diag_messages_count),
                    value = totalMessageCount.toString()
                )
                endpoints.forEach { ep ->
                    DiagnosticRow(
                        label = ep.kind.badge,
                        value = "${ep.ipAddress}:$boundPort"
                    )
                }

                if (fakePeerCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onClearFakePeers,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerCoral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dev_clear_fakes_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dev_clear_fakes_btn))
                    }
                }
            }
        }

        // 5. Bridge Relay Diagnostics Card (shown when bridge mode is enabled or bridge activity exists)
        if (identity.bridgeEnabled || bridgeTelemetry.relayedFramesTotal > 0 || bridgeTelemetry.activeRoutes.isNotEmpty()) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.testTag("dev_bridge_section_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dev_bridge_section),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val isBridgeActive = identity.bridgeEnabled || bridgeTelemetry.isEnabled
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_status_label),
                        value = stringResource(
                            if (isBridgeActive) R.string.dev_bridge_status_active else R.string.dev_bridge_status_off
                        ),
                        valueColor = if (isBridgeActive) OnlineEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_direct_peers),
                        value = if (bridgeTelemetry.directPeerNames.isNotEmpty()) {
                            "${bridgeTelemetry.directSessionCount} (${bridgeTelemetry.directPeerNames.joinToString(", ")})"
                        } else {
                            bridgeTelemetry.directSessionCount.toString()
                        }
                    )
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_advertised_peers),
                        value = if (bridgeTelemetry.advertisedPeerNames.isNotEmpty()) {
                            "${bridgeTelemetry.advertisedPeerCount} (${bridgeTelemetry.advertisedPeerNames.joinToString(", ")})"
                        } else {
                            bridgeTelemetry.advertisedPeerCount.toString()
                        }
                    )
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_relayed_frames),
                        value = bridgeTelemetry.relayedFramesTotal.toString()
                    )
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_relayed_bytes),
                        value = NetworkUtils.formatBytes(bridgeTelemetry.relayedBytesTotal)
                    )
                    DiagnosticRow(
                        label = stringResource(R.string.dev_bridge_failed_relays),
                        value = bridgeTelemetry.failedRelaysTotal.toString(),
                        valueColor = if (bridgeTelemetry.failedRelaysTotal > 0L) WarningAmber else MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.dev_bridge_routes_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (bridgeTelemetry.activeRoutes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dev_bridge_no_routes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        bridgeTelemetry.activeRoutes.forEach { route ->
                            DiagnosticRow(
                                label = "${route.fromName} → ${route.toName}",
                                value = "${route.relayedFrames} · ${NetworkUtils.formatBytes(route.relayedBytes)}"
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelSmall,
                color = valueColor
            )
            if (onCopy != null) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.copy_btn),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
