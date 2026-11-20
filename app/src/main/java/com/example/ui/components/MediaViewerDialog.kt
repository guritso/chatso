package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import java.io.File

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ImmersiveFullscreenDialogWindow() {
    val view = LocalView.current
    val context = LocalContext.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(dialogWindow, view) {
        dialogWindow?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                if (attrs.layoutInDisplayCutoutMode !=
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                ) {
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            }
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // Re-sync underlying Activity insets on dismiss so status/nav bars & IME never stay broken
            dialogWindow?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
            context.findActivity()?.window?.let { activityWindow ->
                WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                ViewCompat.requestApplyInsets(activityWindow.decorView)
            }
        }
    }
}

@Composable
fun FullScreenImageViewerDialog(
    imagePath: String,
    fileName: String?,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showControls by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ImmersiveFullscreenDialogWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        if (nextScale > 1.01f) {
                            val maxPanX = (size.width * (nextScale - 1f)) / 2f
                            val maxPanY = (size.height * (nextScale - 1f)) / 2f
                            offset = Offset(
                                x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                FloatingViewerActions(
                    onOpenExternal = onOpenExternal,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayerDialog(
    videoPath: String,
    fileName: String?,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val context = LocalContext.current
    var playbackError by remember(videoPath) { mutableStateOf<PlaybackException?>(null) }
    var showControls by remember(videoPath) { mutableStateOf(true) }

    val exoPlayer = remember(videoPath) {
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
                showControls = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ImmersiveFullscreenDialogWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        keepScreenOn = true
                        controllerShowTimeoutMs = 3000
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                showControls = visibility == View.VISIBLE
                            }
                        )
                        player = exoPlayer
                    }
                },
                update = { playerView ->
                    if (playerView.player !== exoPlayer) {
                        playerView.player = exoPlayer
                    }
                }
            )

            playbackError?.let { error ->
                Surface(
                    color = Color(0xE6181A1F),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .widthIn(max = 360.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.video_playback_error_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.video_playback_error_body, error.errorCodeName),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.close_btn), color = Color.White)
                            }
                            Button(onClick = onOpenExternal) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.open_external_btn))
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showControls || playbackError != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                FloatingViewerActions(
                    onOpenExternal = onOpenExternal,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun FloatingViewerActions(
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End)
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onOpenExternal,
            modifier = Modifier
                .size(48.dp)
                .testTag("viewer_open_external_button")
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.48f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.open_external_btn),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(48.dp)
                .testTag("viewer_close_button")
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.48f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close_btn),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private data class OpenSourceCredit(
    val library: String,
    val author: String,
    val license: String
)

private val chatsoOpenSourceCredits = listOf(
    OpenSourceCredit("AndroidX Media3 (ExoPlayer & Transformer)", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceCredit("Jetpack Compose & Material 3", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceCredit("AndroidX Room & CameraX", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceCredit("Coil Compose & GIF", "Coil Contributors", "Apache License 2.0"),
    OpenSourceCredit("ZXing Core", "ZXing Authors", "Apache License 2.0"),
    OpenSourceCredit("Kotlin Coroutines", "JetBrains", "Apache License 2.0"),
    OpenSourceCredit("JetBrains Mono Font", "JetBrains", "SIL Open Font License 1.1")
)

@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_source_licenses)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chatsoOpenSourceCredits.forEachIndexed { idx, credit ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = credit.library,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${credit.author} · ${credit.license}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (idx < chatsoOpenSourceCredits.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_btn))
            }
        }
    )
}
