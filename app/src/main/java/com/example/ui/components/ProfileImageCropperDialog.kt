package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.MediaStorageHelper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileImageCropperDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storageHelper = remember(context) { MediaStorageHelper(context) }

    var loadedBitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(sourceUri) { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var zoom by remember(sourceUri) { mutableFloatStateOf(1f) }
    var offsetX by remember(sourceUri) { mutableFloatStateOf(0f) }
    var offsetY by remember(sourceUri) { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(sourceUri) {
        loadFailed = false
        val bmp = storageHelper.loadBitmapForCrop(sourceUri, maxSidePx = 1280)
        if (bmp == null) {
            loadFailed = true
        } else {
            loadedBitmap = bmp
        }
    }

    DisposableEffect(loadedBitmap) {
        val current = loadedBitmap
        onDispose {
            if (current != null && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    fun clampOffsets(bmp: Bitmap, boxSize: Float, currentZoom: Float, rawX: Float, rawY: Float): Pair<Float, Float> {
        if (boxSize <= 0f) return 0f to 0f
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val baseScale = maxOf(boxSize / bw, boxSize / bh)
        val totalScale = baseScale * currentZoom
        val maxX = ((bw * totalScale) - boxSize).coerceAtLeast(0f) / 2f
        val maxY = ((bh * totalScale) - boxSize).coerceAtLeast(0f) / 2f
        return rawX.coerceIn(-maxX, maxX) to rawY.coerceIn(-maxY, maxY)
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.crop_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.crop_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            zoom = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        enabled = loadedBitmap != null && !isSaving
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CenterFocusStrong,
                            contentDescription = stringResource(R.string.crop_reset)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.large)
                        .background(Color(0xFF101318))
                        .onSizeChanged { viewportPx = it.width }
                        .pointerInput(loadedBitmap) {
                            val bmp = loadedBitmap ?: return@pointerInput
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val newZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                val (clampedX, clampedY) = clampOffsets(
                                    bmp = bmp,
                                    boxSize = viewportPx.toFloat(),
                                    currentZoom = newZoom,
                                    rawX = offsetX + pan.x,
                                    rawY = offsetY + pan.y
                                )
                                zoom = newZoom
                                offsetX = clampedX
                                offsetY = clampedY
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = loadedBitmap
                    if (loadFailed) {
                        Text(
                            text = stringResource(R.string.crop_load_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    } else if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val boxSize = size.minDimension
                            val bw = bmp.width.toFloat()
                            val bh = bmp.height.toFloat()
                            val baseScale = maxOf(boxSize / bw, boxSize / bh)
                            val totalScale = baseScale * zoom

                            withTransform({
                                translate(
                                    left = size.width / 2f + offsetX,
                                    top = size.height / 2f + offsetY
                                )
                                scale(scaleX = totalScale, scaleY = totalScale, pivot = Offset.Zero)
                                translate(left = -bw / 2f, top = -bh / 2f)
                            }) {
                                drawImage(imageBitmap)
                            }

                            // Dark circular mask outside the avatar circle
                            val circleRadius = boxSize * 0.46f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val overlayPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, size.width, size.height))
                                addOval(
                                    Rect(
                                        left = center.x - circleRadius,
                                        top = center.y - circleRadius,
                                        right = center.x + circleRadius,
                                        bottom = center.y + circleRadius
                                    )
                                )
                            }
                            drawPath(overlayPath, color = Color.Black.copy(alpha = 0.56f))
                            drawCircle(
                                color = Color.White.copy(alpha = 0.88f),
                                radius = circleRadius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Zoom Slider for precise single-finger alignment
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val bmp = loadedBitmap ?: return@IconButton
                            val nextZoom = (zoom - 0.35f).coerceIn(1f, 5f)
                            val (cx, cy) = clampOffsets(bmp, viewportPx.toFloat(), nextZoom, offsetX, offsetY)
                            zoom = nextZoom
                            offsetX = cx
                            offsetY = cy
                        },
                        enabled = loadedBitmap != null && !isSaving
                    ) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Slider(
                        value = zoom,
                        onValueChange = { nextZoom ->
                            val bmp = loadedBitmap ?: return@Slider
                            val clampedZoom = nextZoom.coerceIn(1f, 5f)
                            val (cx, cy) = clampOffsets(bmp, viewportPx.toFloat(), clampedZoom, offsetX, offsetY)
                            zoom = clampedZoom
                            offsetX = cx
                            offsetY = cy
                        },
                        valueRange = 1f..5f,
                        enabled = loadedBitmap != null && !isSaving,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val bmp = loadedBitmap ?: return@IconButton
                            val nextZoom = (zoom + 0.35f).coerceIn(1f, 5f)
                            val (cx, cy) = clampOffsets(bmp, viewportPx.toFloat(), nextZoom, offsetX, offsetY)
                            zoom = nextZoom
                            offsetX = cx
                            offsetY = cy
                        },
                        enabled = loadedBitmap != null && !isSaving
                    ) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("crop_cancel_button")
                    ) {
                        Text(
                            text = stringResource(R.string.cancel_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = {
                            val bmp = loadedBitmap ?: return@Button
                            val boxSize = viewportPx.toFloat().coerceAtLeast(1f)
                            isSaving = true
                            scope.launch {
                                val resultUri = withContext(Dispatchers.IO) {
                                    renderCroppedAvatarUri(
                                        cacheDir = context.cacheDir,
                                        source = bmp,
                                        boxSize = boxSize,
                                        zoom = zoom,
                                        offsetX = offsetX,
                                        offsetY = offsetY
                                    )
                                }
                                isSaving = false
                                if (resultUri != null) {
                                    onCropped(resultUri)
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                        enabled = loadedBitmap != null && !isSaving,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("crop_apply_button")
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = stringResource(R.string.apply_btn),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

private fun renderCroppedAvatarUri(
    cacheDir: File,
    source: Bitmap,
    boxSize: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): Uri? {
    return try {
        val outPx = 1024
        // Circle radius in preview is 0.46 * boxSize (92% of box); map that circle to fill the square avatar cleanly
        val circleScaleFactor = 1f / 0.92f
        val bw = source.width.toFloat()
        val bh = source.height.toFloat()
        val baseScale = maxOf(boxSize / bw, boxSize / bh)
        val previewScale = baseScale * zoom
        val ratio = (outPx.toFloat() / boxSize) * circleScaleFactor

        val matrix = AndroidMatrix().apply {
            postTranslate(-bw / 2f, -bh / 2f)
            postScale(previewScale * ratio, previewScale * ratio)
            postTranslate(outPx / 2f + offsetX * ratio, outPx / 2f + offsetY * ratio)
        }

        val outBitmap = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(outBitmap)
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        val outFile = File(cacheDir, "cropped_avatar_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { stream ->
            outBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        outBitmap.recycle()
        Uri.fromFile(outFile)
    } catch (_: Exception) {
        null
    }
}
