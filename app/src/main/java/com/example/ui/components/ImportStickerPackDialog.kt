package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.ui.PendingPackImportUiState

@Composable
fun ImportStickerPackDialog(
    state: PendingPackImportUiState,
    onConfirmImport: (packName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var packName by rememberSaveable(state.sourceFile.absolutePath) {
        mutableStateOf(state.probe.suggestedName)
    }

    AlertDialog(
        onDismissRequest = { if (!state.isImporting) onDismiss() },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_sticker_smiley),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.sticker_pack_detected_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.sticker_pack_detected_subtitle,
                        state.probe.author.ifBlank { state.displayNameHint },
                        state.probe.stickerCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.probe.previewStickers.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 176.dp)
                    ) {
                        items(state.probe.previewStickers) { bytes ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.aspectRatio(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
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
                    value = packName,
                    onValueChange = { packName = it },
                    singleLine = true,
                    enabled = !state.isImporting,
                    label = { Text(stringResource(R.string.sticker_pack_name_label)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_pack_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmImport(packName) },
                enabled = !state.isImporting && packName.isNotBlank(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_import_pack_button")
            ) {
                if (state.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.sticker_pack_import_confirm, state.probe.stickerCount))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isImporting,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.cancel_btn))
            }
        }
    )
}
