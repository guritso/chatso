package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.data.FavoriteStickerEntity
import com.example.data.PackStickerEntity
import com.example.data.StickerPackEntity
import com.example.ui.theme.AppIconManager
import com.example.ui.theme.DangerCoral
import java.io.File

enum class ComposerTrayMode {
    NONE,
    STICKERS,
    ATTACHMENTS
}

private data class UnifiedStickerEntry(
    val key: String,
    val localPath: String,
    val mimeType: String,
    val sha256: String,
    val favoriteEntity: FavoriteStickerEntity?,
    val packStickerEntity: PackStickerEntity?,
    val isFavorited: Boolean
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickersTrayContent(
    favoriteStickers: List<FavoriteStickerEntity>,
    onSendFavoriteSticker: (FavoriteStickerEntity) -> Unit,
    onRemoveFavoriteSticker: (String) -> Unit,
    stickerPacks: List<StickerPackEntity> = emptyList(),
    packStickers: List<PackStickerEntity> = emptyList(),
    onSendStickerFile: (localPath: String, mimeType: String) -> Unit = { _, _ -> },
    onToggleFavoriteStickerFile: (localPath: String, mimeType: String) -> Unit = { _, _ -> },
    onRemovePackSticker: (String) -> Unit = {},
    onRenameStickerPack: (packId: String, newName: String) -> Unit = { _, _ -> },
    onDeleteStickerPack: (String) -> Unit = {},
    onPickStickerPackUri: (Uri) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTabKey by rememberSaveable { mutableStateOf("all") }
    var actionSticker by remember { mutableStateOf<UnifiedStickerEntry?>(null) }
    var pendingRenamePack by remember { mutableStateOf<StickerPackEntity?>(null) }
    var pendingDeletePack by remember { mutableStateOf<StickerPackEntity?>(null) }

    val packArchivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onPickStickerPackUri(uri)
        }
    }

    val activePack: StickerPackEntity? = if (selectedTabKey.startsWith("pack:")) {
        val packId = selectedTabKey.removePrefix("pack:")
        stickerPacks.firstOrNull { it.packId == packId }
    } else {
        null
    }
    val effectiveTabKey = when {
        selectedTabKey == "favorites" -> "favorites"
        activePack != null -> selectedTabKey
        else -> "all"
    }

    val favoriteBySha = remember(favoriteStickers) {
        favoriteStickers.associateBy { it.sha256 }
    }

    val allUnified = remember(favoriteStickers, packStickers, favoriteBySha) {
        val seenSha = HashSet<String>()
        val list = mutableListOf<UnifiedStickerEntry>()
        favoriteStickers.forEach { fav ->
            if (seenSha.add(fav.sha256)) {
                list += UnifiedStickerEntry(
                    key = "fav_${fav.stickerId}",
                    localPath = fav.localPath,
                    mimeType = fav.mimeType,
                    sha256 = fav.sha256,
                    favoriteEntity = fav,
                    packStickerEntity = null,
                    isFavorited = true
                )
            }
        }
        packStickers.forEach { ps ->
            if (seenSha.add(ps.sha256)) {
                val favMatch = favoriteBySha[ps.sha256]
                list += UnifiedStickerEntry(
                    key = "pack_${ps.stickerId}",
                    localPath = ps.localPath,
                    mimeType = ps.mimeType,
                    sha256 = ps.sha256,
                    favoriteEntity = favMatch,
                    packStickerEntity = ps,
                    isFavorited = favMatch != null
                )
            }
        }
        list
    }

    val displayedStickers = remember(effectiveTabKey, allUnified, favoriteStickers, packStickers, favoriteBySha) {
        when {
            effectiveTabKey == "all" -> allUnified
            effectiveTabKey == "favorites" -> favoriteStickers.map { fav ->
                UnifiedStickerEntry(
                    key = "fav_${fav.stickerId}",
                    localPath = fav.localPath,
                    mimeType = fav.mimeType,
                    sha256 = fav.sha256,
                    favoriteEntity = fav,
                    packStickerEntity = null,
                    isFavorited = true
                )
            }
            activePack != null -> packStickers
                .filter { it.packId == activePack.packId }
                .map { ps ->
                    val favMatch = favoriteBySha[ps.sha256]
                    UnifiedStickerEntry(
                        key = "pack_${ps.stickerId}",
                        localPath = ps.localPath,
                        mimeType = ps.mimeType,
                        sha256 = ps.sha256,
                        favoriteEntity = favMatch,
                        packStickerEntity = ps,
                        isFavorited = favMatch != null
                    )
                }
            else -> allUnified
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxSize()
            .testTag("stickers_tray")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "tab_all") {
                    StickerFilterPill(
                        label = stringResource(R.string.stickers_tab_all),
                        badgeCount = allUnified.size.takeIf { it > 0 },
                        selected = effectiveTabKey == "all",
                        iconPath = null,
                        leadingIconVector = null,
                        onClick = { selectedTabKey = "all" },
                        modifier = Modifier.testTag("stickers_tab_all")
                    )
                }
                item(key = "tab_favorites") {
                    StickerFilterPill(
                        label = stringResource(R.string.stickers_tab_favorites),
                        badgeCount = favoriteStickers.size.takeIf { it > 0 },
                        selected = effectiveTabKey == "favorites",
                        iconPath = null,
                        leadingIconVector = Icons.Filled.Star,
                        onClick = { selectedTabKey = "favorites" },
                        modifier = Modifier.testTag("stickers_tab_favorites")
                    )
                }
                items(stickerPacks, key = { "pack_${it.packId}" }) { pack ->
                    val tabKey = "pack:${pack.packId}"
                    StickerFilterPill(
                        label = pack.name,
                        badgeCount = null,
                        selected = effectiveTabKey == tabKey,
                        iconPath = pack.iconPath,
                        leadingIconVector = null,
                        onClick = { selectedTabKey = tabKey },
                        onLongClick = { pendingRenamePack = pack },
                        modifier = Modifier.testTag("stickers_tab_pack_${pack.packId}")
                    )
                }
                item(key = "tab_import") {
                    Surface(
                        onClick = {
                            AppIconManager.suppressNextFlush()
                            packArchivePickerLauncher.launch(arrayOf("*/*"))
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stickers_import_pack_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.stickers_import_btn),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.stickers_import_btn),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (activePack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activePack.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val metaLine = buildString {
                            append(stringResource(R.string.stickers_pack_count, displayedStickers.size))
                            if (activePack.author.isNotBlank()) {
                                append(" · ")
                                append(activePack.author)
                            }
                        }
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { pendingRenamePack = activePack },
                            modifier = Modifier.testTag("sticker_pack_rename_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.sticker_pack_rename_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { pendingDeletePack = activePack },
                            modifier = Modifier.testTag("sticker_pack_delete_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.delete_message_action),
                                tint = DangerCoral,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            if (displayedStickers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sticker_smiley),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.favorite_stickers_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (effectiveTabKey == "favorites") {
                                stringResource(R.string.favorite_stickers_empty_body)
                            } else {
                                stringResource(R.string.stickers_all_empty_body)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (effectiveTabKey == "all") {
                            OutlinedButton(
                                onClick = {
                                    AppIconManager.suppressNextFlush()
                                    packArchivePickerLauncher.launch(arrayOf("*/*"))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("stickers_empty_import_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.stickers_import_btn))
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = displayedStickers,
                        key = { it.key }
                    ) { entry ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (entry.favoriteEntity != null && entry.packStickerEntity == null) {
                                            onSendFavoriteSticker(entry.favoriteEntity)
                                        } else {
                                            onSendStickerFile(entry.localPath, entry.mimeType)
                                        }
                                    },
                                    onLongClick = { actionSticker = entry }
                                )
                                .testTag("sticker_grid_item_${entry.key}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = File(entry.localPath),
                                    contentDescription = stringResource(R.string.sticker_label),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionSticker?.let { target ->
        AlertDialog(
            onDismissRequest = { actionSticker = null },
            title = { Text(stringResource(R.string.sticker_actions_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = File(target.localPath),
                                contentDescription = stringResource(R.string.sticker_label),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (target.favoriteEntity != null && effectiveTabKey == "favorites") {
                                onRemoveFavoriteSticker(target.favoriteEntity.stickerId)
                            } else {
                                onToggleFavoriteStickerFile(target.localPath, target.mimeType)
                            }
                            actionSticker = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (target.isFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (target.isFavorited) {
                                stringResource(R.string.remove_favorite_sticker_btn)
                            } else {
                                stringResource(R.string.sticker_add_favorite_btn)
                            }
                        )
                    }

                    if (target.packStickerEntity != null) {
                        TextButton(
                            onClick = {
                                onRemovePackSticker(target.packStickerEntity.stickerId)
                                actionSticker = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = DangerCoral),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.sticker_remove_from_pack_btn))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionSticker = null }) {
                    Text(stringResource(R.string.close_btn))
                }
            }
        )
    }

    pendingRenamePack?.let { pack ->
        var draftName by remember(pack.packId) { mutableStateOf(pack.name) }
        AlertDialog(
            onDismissRequest = { pendingRenamePack = null },
            title = { Text(stringResource(R.string.sticker_pack_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sticker_pack_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameStickerPack(pack.packId, draftName)
                        pendingRenamePack = null
                    },
                    enabled = draftName.isNotBlank()
                ) {
                    Text(stringResource(R.string.save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenamePack = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }

    pendingDeletePack?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDeletePack = null },
            title = { Text(stringResource(R.string.sticker_pack_delete_title, pack.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteStickerPack(pack.packId)
                        pendingDeletePack = null
                        selectedTabKey = "all"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerCoral)
                ) {
                    Text(stringResource(R.string.delete_message_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePack = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerFilterPill(
    label: String,
    badgeCount: Int?,
    selected: Boolean,
    iconPath: String?,
    leadingIconVector: ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!iconPath.isNullOrBlank() && File(iconPath).exists()) {
                AsyncImage(
                    model = File(iconPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else if (leadingIconVector != null) {
                Icon(
                    imageVector = leadingIconVector,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (badgeCount != null) {
                Text(
                    text = badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun AttachmentsMenuTray(
    selfDestructSeconds: Int,
    selfDestructLabel: String,
    onDismiss: () -> Unit,
    onSendPhotoUri: (Uri) -> Unit,
    onSendVideoUri: (Uri) -> Unit,
    onSendDocumentUri: (Uri) -> Unit,
    onOpenSelfDestruct: () -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraDeniedMsg = stringResource(R.string.msg_camera_denied)
    val cameraUnavailableMsg = stringResource(R.string.msg_camera_unavailable)

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            pendingCameraUri = null
            pendingCameraFile = null
            onSendPhotoUri(uri)
            onDismiss()
        } else {
            runCatching { pendingCameraFile?.delete() }
            pendingCameraUri = null
            pendingCameraFile = null
        }
    }

    fun launchSystemCamera() {
        val camDir = File(context.cacheDir, "cam_photos").apply { if (!exists()) mkdirs() }
        val outFile = File(camDir, "chatso_cam_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        }.getOrNull()
        if (uri == null) {
            onNotify(cameraUnavailableMsg)
            return
        }
        pendingCameraFile = outFile
        pendingCameraUri = uri
        AppIconManager.suppressNextFlush()
        runCatching {
            takePictureLauncher.launch(uri)
        }.onFailure {
            runCatching { outFile.delete() }
            pendingCameraFile = null
            pendingCameraUri = null
            onNotify(cameraUnavailableMsg)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSystemCamera()
        } else {
            onNotify(cameraDeniedMsg)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                if (mime?.startsWith("video/") == true) {
                    onSendVideoUri(uri)
                } else {
                    onSendPhotoUri(uri)
                }
            }
            onDismiss()
        }
    }

    val documentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(onSendDocumentUri)
            onDismiss()
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxSize()
            .testTag("attachments_tray")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AttachmentSquareTile(
                    label = stringResource(R.string.tab_camera),
                    icon = Icons.Filled.CameraAlt,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        val hasCam = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasCam) {
                            launchSystemCamera()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .testTag("attach_tile_camera")
                )

                AttachmentSquareTile(
                    label = stringResource(R.string.attach_gallery),
                    icon = Icons.Filled.PhotoLibrary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        AppIconManager.suppressNextFlush()
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .testTag("attach_tile_gallery")
                )

                AttachmentSquareTile(
                    label = stringResource(R.string.tab_files),
                    icon = Icons.Filled.FolderOpen,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        AppIconManager.suppressNextFlush()
                        documentsLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .testTag("attach_tile_files")
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AttachmentListOptionTile(
                    title = stringResource(R.string.self_destruct_title),
                    subtitle = "${stringResource(R.string.self_destruct_desc)} · $selfDestructLabel",
                    icon = Icons.Filled.Timer,
                    isActive = selfDestructSeconds > 0,
                    onClick = {
                        onDismiss()
                        onOpenSelfDestruct()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("attach_option_self_destruct")
                )
            }
        }
    }
}

@Composable
private fun AttachmentSquareTile(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AttachmentListOptionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
