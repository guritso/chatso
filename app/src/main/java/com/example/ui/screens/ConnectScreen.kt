package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.DiscoveredPeer
import com.example.data.LocalIdentity
import com.example.data.NetworkEndpointInfo
import com.example.network.QrCodeHelper
import com.example.ui.components.ChatsoAvatar
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.StealthIndigo
import java.util.concurrent.Executors

@Composable
fun ConnectScreen(
    identity: LocalIdentity,
    endpoints: List<NetworkEndpointInfo>,
    boundPort: Int,
    discoveredPeers: List<DiscoveredPeer>,
    isScanning: Boolean,
    isConnecting: Boolean,
    onConnectManualIp: (ip: String, port: String) -> Unit,
    onConnectDiscoveredPeer: (DiscoveredPeer) -> Unit,
    onConnectQrRaw: (String) -> Unit,
    onDecodeQrFromImage: (Uri) -> Unit,
    onScanNetwork: () -> Unit,
    onRotateQrToken: () -> Unit,
    onToggleDiscoverable: (Boolean) -> Unit,
    onNotify: (String) -> Unit
) {
    // Primary option is "On Network" (index 0)
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    val modes = listOf(
        stringResource(R.string.connect_lan_tab),
        stringResource(R.string.connect_qr_tab),
        stringResource(R.string.connect_manual_tab)
    )

    // Auto-scan swiftly once when opening the default "On Network" tab
    LaunchedEffect(Unit) {
        if (discoveredPeers.isEmpty() && !isScanning) {
            onScanNetwork()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = modeIndex == index,
                    onClick = { modeIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    modifier = Modifier.testTag("connect_mode_$index")
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (modeIndex) {
            0 -> LanDiscoveryPane(
                identity = identity,
                discoveredPeers = discoveredPeers,
                isScanning = isScanning,
                isConnecting = isConnecting,
                onScanNetwork = onScanNetwork,
                onConnectPeer = onConnectDiscoveredPeer,
                onToggleDiscoverable = onToggleDiscoverable
            )
            1 -> QrConnectPane(
                identity = identity,
                endpoints = endpoints,
                boundPort = boundPort,
                onConnectQrRaw = onConnectQrRaw,
                onDecodeQrFromImage = onDecodeQrFromImage,
                onRotateQrToken = onRotateQrToken,
                onNotify = onNotify
            )
            2 -> ManualIpConnectPane(
                endpoints = endpoints,
                boundPort = boundPort,
                isConnecting = isConnecting,
                onConnectManualIp = onConnectManualIp,
                onNotify = onNotify
            )
        }
    }
}

@Composable
private fun LanDiscoveryPane(
    identity: LocalIdentity,
    discoveredPeers: List<DiscoveredPeer>,
    isScanning: Boolean,
    isConnecting: Boolean,
    onScanNetwork: () -> Unit,
    onConnectPeer: (DiscoveredPeer) -> Unit,
    onToggleDiscoverable: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (identity.isDiscoverable) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    StealthIndigo.copy(alpha = 0.12f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleDiscoverable(!identity.isDiscoverable) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (identity.isDiscoverable) {
                        Icons.Filled.Visibility
                    } else {
                        Icons.Filled.VisibilityOff
                    },
                    contentDescription = null,
                    tint = if (identity.isDiscoverable) OnlineEmerald else StealthIndigo,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (identity.isDiscoverable) {
                        stringResource(R.string.discoverable_on_desc)
                    } else {
                        stringResource(R.string.discoverable_off_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onScanNetwork,
            enabled = !isScanning,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("connect_scan_network_button")
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.scanning_network),
                    maxLines = 1,
                    softWrap = false
                )
            } else {
                Icon(imageVector = Icons.Filled.Radar, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.scan_network_btn),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (discoveredPeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_peers_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(discoveredPeers, key = { it.peerId }) { peer ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChatsoAvatar(name = peer.username, avatarModel = null, size = 42.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.username,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${peer.ipAddress}:${peer.port}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onConnectPeer(peer) },
                                enabled = !isConnecting,
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.connect_btn),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrConnectPane(
    identity: LocalIdentity,
    endpoints: List<NetworkEndpointInfo>,
    boundPort: Int,
    onConnectQrRaw: (String) -> Unit,
    onDecodeQrFromImage: (Uri) -> Unit,
    onRotateQrToken: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var pastedInviteText by rememberSaveable { mutableStateOf("") }
    var selectedIpIndex by rememberSaveable { mutableIntStateOf(0) }
    val activeIp = endpoints.getOrNull(selectedIpIndex)?.ipAddress
        ?: endpoints.firstOrNull()?.ipAddress
        ?: "127.0.0.1"

    val inviteUri = remember(activeIp, boundPort, identity.publicKeyBase64, identity.username, identity.qrInviteToken) {
        QrCodeHelper.buildInviteUri(
            ip = activeIp,
            port = boundPort,
            publicKeyBase64 = identity.publicKeyBase64,
            username = identity.username,
            inviteToken = identity.qrInviteToken
        )
    }

    val qrBitmap = remember(inviteUri) {
        QrCodeHelper.generateQrBitmap(inviteUri, 640)
    }

    val copiedMsg = stringResource(R.string.msg_copied)
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val qrGalleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onDecodeQrFromImage(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !showScanner,
                onClick = { showScanner = false },
                leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(R.string.my_qr_tab),
                        maxLines = 1,
                        softWrap = false
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("qr_show_my_card_chip")
            )
            FilterChip(
                selected = showScanner,
                onClick = {
                    showScanner = true
                    if (!hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(R.string.scan_qr_tab),
                        maxLines = 1,
                        softWrap = false
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("qr_open_scanner_chip")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!showScanner) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = identity.username,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$activeIp:$boundPort",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (endpoints.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            endpoints.take(3).forEachIndexed { idx, ep ->
                                FilterChip(
                                    selected = selectedIpIndex == idx,
                                    onClick = { selectedIpIndex = idx },
                                    label = {
                                        Text(
                                            text = ep.kind.badge,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(Color.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.connect_qr_tab),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Chatso", inviteUri)
                                )
                                onNotify(copiedMsg)
                            },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.copy_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        OutlinedButton(
                            onClick = onRotateQrToken,
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.renew_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        } else {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(Color.Black)
                        ) {
                            CameraQrScannerPreview(
                                onQrDetected = { raw ->
                                    showScanner = false
                                    onConnectQrRaw(raw)
                                }
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.camera_permission_msg),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        ) {
                            Text(
                                text = stringResource(R.string.allow_camera_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            qrGalleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qr_pick_from_gallery_button")
                    ) {
                        Icon(Icons.Filled.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.pick_qr_image_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val emptyClipboardMsg = stringResource(R.string.msg_clipboard_empty)
                    OutlinedTextField(
                        value = pastedInviteText,
                        onValueChange = { pastedInviteText = it },
                        label = { Text(stringResource(R.string.paste_qr_label)) },
                        placeholder = { Text(stringResource(R.string.paste_qr_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qr_paste_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipText = clipboard.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    ?.trim()
                                    .orEmpty()
                                if (clipText.isBlank()) {
                                    onNotify(emptyClipboardMsg)
                                } else {
                                    pastedInviteText = clipText
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("qr_paste_clipboard_button")
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.paste_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = {
                                showScanner = false
                                onConnectQrRaw(pastedInviteText.trim())
                            },
                            enabled = pastedInviteText.isNotBlank(),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("qr_connect_pasted_button")
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.connect_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualIpConnectPane(
    endpoints: List<NetworkEndpointInfo>,
    boundPort: Int,
    isConnecting: Boolean,
    onConnectManualIp: (String, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var ipInput by rememberSaveable { mutableStateOf("") }
    var portInput by rememberSaveable(boundPort) { mutableStateOf(boundPort.toString()) }
    val copiedMsg = stringResource(R.string.msg_copied)
    val preferredEp = endpoints.firstOrNull()
    val preferredAddr = "${preferredEp?.ipAddress ?: "127.0.0.1"}:$boundPort"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it.trim() },
                    label = { Text(stringResource(R.string.ip_address_label)) },
                    placeholder = { Text(stringResource(R.string.ip_address_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connect_ip_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connect_port_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                AssistChip(
                    onClick = {
                        ipInput = "127.0.0.1"
                        portInput = boundPort.toString()
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.use_localhost_chip),
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    modifier = Modifier.testTag("connect_fill_localhost_chip")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onConnectManualIp(ipInput, portInput) },
                    enabled = !isConnecting && ipInput.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("connect_socket_button")
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.connecting_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.connect_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Compact 1-line copyable IP address strip
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.your_ip_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = preferredAddr,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Chatso IP", preferredAddr))
                        onNotify(copiedMsg)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.copy_btn)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraQrScannerPreview(onQrDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var alreadyEmitted by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                val decoded = QrCodeHelper.decodeFromImageProxy(imageProxy)
                                if (!decoded.isNullOrBlank() && !alreadyEmitted) {
                                    alreadyEmitted = true
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onQrDetected(decoded)
                                    }
                                }
                            }
                        }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}
