package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.BuildConfig
import com.example.R
import com.example.data.AppLanguage
import com.example.data.LocalIdentity
import com.example.data.NetworkEndpointInfo
import com.example.data.ThemeMode
import com.example.data.ThemePalette
import com.example.network.NetworkUtils
import com.example.ui.components.ChatsoAvatar
import com.example.ui.components.OpenSourceLicensesDialog
import com.example.ui.components.ProfileImageCropperDialog
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OnlineEmerald
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.spec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSettingsScreen(
    identity: LocalIdentity,
    endpoints: List<NetworkEndpointInfo>,
    boundPort: Int,
    onSaveProfile: (username: String, newAvatarUri: Uri?, keepExisting: Boolean) -> Unit,
    onToggleDiscoverable: (Boolean) -> Unit,
    onToggleBlockUnknown: (Boolean) -> Unit,
    onToggleBackgroundConnection: (Boolean) -> Unit,
    onUpdateListenPort: (String) -> Unit,
    onChangeTheme: (ThemeMode) -> Unit,
    onChangePalette: (ThemePalette) -> Unit,
    onToggleThemeAdvanced: (Boolean) -> Unit,
    onChangeLanguage: (AppLanguage) -> Unit,
    onCheckUpdates: () -> Unit,
    isCheckingUpdates: Boolean,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    var editedUsername by rememberSaveable(identity.username) { mutableStateOf(identity.username) }
    var pickedAvatarUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCropUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var portInput by rememberSaveable(identity.listenPort) {
        mutableStateOf(identity.listenPort.toString())
    }
    var isIgnoringBattery by remember {
        mutableStateOf(NetworkUtils.isIgnoringBatteryOptimizations(context))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isIgnoringBattery = NetworkUtils.isIgnoringBatteryOptimizations(context)
    }

    val pickedUri = pickedAvatarUriString?.let { Uri.parse(it) }
    val pendingCropUri = pendingCropUriString?.let { Uri.parse(it) }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingCropUriString = uri.toString()
        }
    }

    if (pendingCropUri != null) {
        ProfileImageCropperDialog(
            sourceUri = pendingCropUri,
            onDismiss = { pendingCropUriString = null },
            onCropped = { croppedUri ->
                pickedAvatarUriString = croppedUri.toString()
                pendingCropUriString = null
            }
        )
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val copiedMsg = stringResource(R.string.msg_copied)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        // 1. Local Profile Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ChatsoAvatar(
                        name = editedUsername,
                        avatarModel = pickedUri ?: identity.avatarPath,
                        size = 56.dp,
                        modifier = Modifier
                            .clickable {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .testTag("profile_avatar_button")
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = editedUsername,
                        onValueChange = { editedUsername = it.take(36) },
                        label = { Text(stringResource(R.string.username_label)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("profile_username_input")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddAPhoto,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.change_photo),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            onSaveProfile(editedUsername, pickedUri, pickedUri == null)
                            pickedAvatarUriString = null
                        },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("profile_save_button")
                    ) {
                        Text(
                            text = stringResource(R.string.save_btn),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2. Privacy & Invisible Mode Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.privacy_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleDiscoverable(!identity.isDiscoverable) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.discoverable_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (identity.isDiscoverable) {
                                stringResource(R.string.discoverable_on_desc)
                            } else {
                                stringResource(R.string.discoverable_off_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = identity.isDiscoverable,
                        onCheckedChange = onToggleDiscoverable,
                        modifier = Modifier.testTag("profile_discoverable_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleBlockUnknown(!identity.blockUnknownPeers) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.block_unknown_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.block_unknown_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = identity.blockUnknownPeers,
                        onCheckedChange = onToggleBlockUnknown,
                        modifier = Modifier.testTag("profile_block_unknown_switch")
                    )
                }
            }
        }

        // 2B. Background Connection & Battery Optimization Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.battery_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleBackgroundConnection(!identity.backgroundConnectionEnabled) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.bg_conn_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.bg_conn_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = identity.backgroundConnectionEnabled,
                        onCheckedChange = onToggleBackgroundConnection,
                        modifier = Modifier.testTag("profile_bg_connection_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.battery_opt_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (isIgnoringBattery) {
                                stringResource(R.string.battery_opt_ignored)
                            } else {
                                stringResource(R.string.battery_opt_active)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isIgnoringBattery) OnlineEmerald else WarningAmber
                        )
                    }
                    if (!isIgnoringBattery) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { NetworkUtils.openBatteryOptimizationRequest(context) },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("profile_battery_disable_button")
                        ) {
                            Text(
                                text = stringResource(R.string.battery_opt_disable_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // 3. Appearance & Night Mode Card — SingleChoiceSegmentedButtonRow prevents wrapping
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (identity.themeMode == ThemeMode.DARK) {
                            Icons.Filled.DarkMode
                        } else {
                            Icons.Filled.LightMode
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.theme_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val themeEntries = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_auto),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeEntries.forEachIndexed { idx, (mode, label) ->
                        SegmentedButton(
                            selected = identity.themeMode == mode,
                            onClick = { onChangeTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = themeEntries.size
                            ),
                            modifier = Modifier.testTag("profile_theme_${mode.name.lowercase()}")
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

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                val activePaletteName = paletteDisplayName(identity.themePalette)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.theme_palette_label),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (identity.themePalette == ThemePalette.PURPLE) {
                                "$activePaletteName · ${stringResource(R.string.palette_default_badge)}"
                            } else {
                                activePaletteName
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePalette.entries.forEach { palette ->
                        val isSelected = identity.themePalette == palette
                        val spec = palette.spec()
                        val label = paletteDisplayName(palette)
                        val ringWidth by animateDpAsState(
                            targetValue = if (isSelected) 2.5.dp else 1.dp,
                            animationSpec = spring(),
                            label = "paletteRingWidth"
                        )
                        val ringColor by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            label = "paletteRingColor"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(min = 68.dp)
                                .clip(MaterialTheme.shapes.small)
                                .minimumInteractiveComponentSize()
                                .semantics {
                                    selected = isSelected
                                }
                                .clickable(
                                    role = Role.RadioButton,
                                    onClickLabel = label
                                ) {
                                    onChangePalette(palette)
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .testTag("profile_palette_${palette.name.lowercase()}")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(46.dp)
                                    .border(ringWidth, ringColor, CircleShape)
                                    .padding(4.dp)
                            ) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                ) {
                                    drawCircle(color = spec.swatchPrimary)
                                    val halfPath = Path().apply {
                                        moveTo(size.width, 0f)
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height)
                                        close()
                                    }
                                    drawPath(path = halfPath, color = spec.swatchSecondary)
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.35f),
                                        start = Offset(size.width, 0f),
                                        end = Offset(0f, size.height),
                                        strokeWidth = 1.5f
                                    )
                                }
                                if (isSelected) {
                                    Surface(
                                        shape = CircleShape,
                                        color = spec.light.primary.copy(alpha = 0.92f),
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = label,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onToggleThemeAdvanced(!identity.themeAdvanced) }
                        .padding(vertical = 4.dp)
                        .testTag("profile_theme_advanced_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.theme_advanced_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.theme_advanced_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = identity.themeAdvanced,
                        onCheckedChange = onToggleThemeAdvanced,
                        modifier = Modifier.testTag("profile_theme_advanced_switch")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live mini chat preview reacting immediately to palette + advanced mode
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 1.dp
                        ) {
                            Text(
                                text = stringResource(R.string.theme_preview_incoming),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.theme_preview_outgoing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.DoneAll,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Multi-Language Selector Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.language_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val label = if (lang == AppLanguage.SYSTEM) {
                            stringResource(R.string.language_system)
                        } else {
                            lang.nativeName
                        }
                        FilterChip(
                            selected = identity.language == lang,
                            onClick = { onChangeLanguage(lang) },
                            label = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            modifier = Modifier.testTag("profile_lang_${lang.code}")
                        )
                    }
                }
            }
        }

        // 5. Public Key Fingerprint (Compact)
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
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.public_key_section),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Chatso Public Key", identity.publicKeyBase64)
                            )
                            onNotify(copiedMsg)
                        },
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("profile_copy_pubkey_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.copy_key_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = identity.fingerprint,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 6. Network Interfaces & TCP Port
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.network_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                endpoints.forEach { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ep.kind.badge,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${ep.ipAddress}:$boundPort",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.tcp_port_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("profile_port_input")
                    )
                    Button(
                        onClick = { onUpdateListenPort(portInput) },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 88.dp)
                            .height(52.dp)
                            .testTag("profile_apply_port_button")
                    ) {
                        Text(
                            text = stringResource(R.string.apply_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 7. Updates & Open Source Licenses
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.updates_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.current_version_label, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onCheckUpdates,
                        enabled = !isCheckingUpdates,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("profile_check_updates_button")
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.checking_updates),
                                maxLines = 1,
                                softWrap = false
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.check_updates_btn),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLicensesDialog = true }
                        .padding(vertical = 4.dp)
                        .testTag("profile_open_licenses_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.open_source_licenses),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.open_source_licenses_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun paletteDisplayName(palette: ThemePalette): String = when (palette) {
    ThemePalette.PURPLE -> stringResource(R.string.palette_purple)
    ThemePalette.LILAC -> stringResource(R.string.palette_lilac)
    ThemePalette.INDIGO -> stringResource(R.string.palette_indigo)
    ThemePalette.BLUE -> stringResource(R.string.palette_blue)
    ThemePalette.CYAN -> stringResource(R.string.palette_cyan)
    ThemePalette.TEAL -> stringResource(R.string.palette_teal)
    ThemePalette.EMERALD -> stringResource(R.string.palette_emerald)
    ThemePalette.LIME -> stringResource(R.string.palette_lime)
    ThemePalette.AMBER -> stringResource(R.string.palette_amber)
    ThemePalette.ORANGE -> stringResource(R.string.palette_orange)
    ThemePalette.CORAL -> stringResource(R.string.palette_coral)
    ThemePalette.ROSE -> stringResource(R.string.palette_rose)
}
