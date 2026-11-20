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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.ui.theme.AppIconManager
import com.example.ui.theme.DangerCoral
import java.io.File

enum class ComposerTrayMode {
    NONE,
    STICKERS,
    ATTACHMENTS
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickersTrayContent(
    favoriteStickers: List<FavoriteStickerEntity>,
    onSendFavoriteSticker: (FavoriteStickerEntity) -> Unit,
    onRemoveFavoriteSticker: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteSticker by remember { mutableStateOf<FavoriteStickerEntity?>(null) }

    Surface(
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxSize()
            .testTag("stickers_tray")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sticker_smiley),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.favorite_stickers_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (favoriteStickers.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = favoriteStickers.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (favoriteStickers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sticker_smiley),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
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
                            text = stringResource(R.string.favorite_stickers_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
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
                        items = favoriteStickers,
                        key = { it.stickerId }
                    ) { sticker ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onSendFavoriteSticker(sticker) },
                                    onLongClick = { pendingDeleteSticker = sticker }
                                )
                                .testTag("favorite_sticker_item_${sticker.stickerId}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = File(sticker.localPath),
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

    pendingDeleteSticker?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSticker = null },
            title = { Text(stringResource(R.string.remove_favorite_sticker_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveFavoriteSticker(target.stickerId)
                        pendingDeleteSticker = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerCoral
                    )
                ) {
                    Text(stringResource(R.string.remove_favorite_sticker_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSticker = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        )
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
