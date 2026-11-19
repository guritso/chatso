package com.example.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class VideoProbeInfo(
    val width: Int,
    val height: Int,
    val shortSide: Int,
    val longSide: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val videoMime: String?,
    val hasAudio: Boolean,
    val frameRate: Int,
    val isHdr: Boolean
)

object VideoCompressor {
    private const val MAX_DIRECT_BYTES = 4L * 1024L * 1024L // 4 MB
    private const val TARGET_SHORT_SIDE_PX = 720
    private const val TARGET_MAX_LONG_SIDE_PX = 1280
    private const val TARGET_VIDEO_BITRATE = 1_800_000 // ~1.8 Mbps -> ~3.3 MB for 14s
    private const val TARGET_MAX_FPS = 30f

    fun probeVideo(file: File): VideoProbeInfo? {
        if (!file.exists() || file.length() <= 0L) return null
        var width = 0
        var height = 0
        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        var videoMime: String? = null
        var hasAudio = false
        var frameRate = 0
        var isHdr = false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    if (width <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
                            .getOrElse {
                                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }
                                    .getOrDefault(0)
                            }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                    ) {
                        val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                            transfer == MediaFormat.COLOR_TRANSFER_HLG
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        return VideoProbeInfo(
            width = width,
            height = height,
            shortSide = shortSide,
            longSide = longSide,
            durationMs = durationMs,
            fileSizeBytes = file.length(),
            videoMime = videoMime,
            hasAudio = hasAudio,
            frameRate = frameRate,
            isHdr = isHdr
        )
    }

    fun shouldCompress(file: File, probe: VideoProbeInfo?): Boolean {
        if (probe == null) return file.length() > MAX_DIRECT_BYTES
        val isMp4 = file.extension.equals("mp4", ignoreCase = true)
        val isAvc = probe.videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
        val withinBounds = probe.shortSide in 1..TARGET_SHORT_SIDE_PX &&
            probe.longSide in 1..TARGET_MAX_LONG_SIDE_PX
        val normalFps = probe.frameRate <= 35
        return !isMp4 ||
            !isAvc ||
            probe.isHdr ||
            !withinBounds ||
            !normalFps ||
            probe.fileSizeBytes > MAX_DIRECT_BYTES
    }

    /**
     * Compresses/transcodes a video to a universally playable 720p SDR H.264/AAC MP4.
     * Returns true if [outputFile] was written and should be used instead of [inputFile].
     */
    @OptIn(UnstableApi::class)
    suspend fun compressIfNeeded(
        context: Context,
        inputFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        val probe = withContext(Dispatchers.IO) { probeVideo(inputFile) }
        if (!shouldCompress(inputFile, probe)) {
            return false
        }

        val primaryOk = runTransformerExport(
            context = context,
            inputFile = inputFile,
            outputFile = outputFile,
            probe = probe,
            hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            useCustomEncoderSettings = true,
            onProgress = onProgress
        )
        if (primaryOk && isValidCompressedOutput(inputFile, outputFile, probe)) {
            return true
        }
        runCatching { if (outputFile.exists()) outputFile.delete() }

        // Fallback attempt: interpret HDR as SDR & let Media3 choose default encoder profile
        val fallbackOk = runTransformerExport(
            context = context,
            inputFile = inputFile,
            outputFile = outputFile,
            probe = probe,
            hdrMode = Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
            useCustomEncoderSettings = false,
            onProgress = onProgress
        )
        if (fallbackOk && isValidCompressedOutput(inputFile, outputFile, probe)) {
            return true
        }
        runCatching { if (outputFile.exists()) outputFile.delete() }
        return false
    }

    private fun isValidCompressedOutput(
        inputFile: File,
        outputFile: File,
        probe: VideoProbeInfo?
    ): Boolean {
        if (!outputFile.exists() || outputFile.length() <= 1024L) return false
        val mustTranscodeForCompat = probe == null ||
            !probe.videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
            probe.isHdr ||
            probe.shortSide > TARGET_SHORT_SIDE_PX
        return mustTranscodeForCompat || outputFile.length() < inputFile.length()
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformerExport(
        context: Context,
        inputFile: File,
        outputFile: File,
        probe: VideoProbeInfo?,
        hdrMode: Int,
        useCustomEncoderSettings: Boolean,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            val mainHandler = Handler(Looper.getMainLooper())
            var finished = false
            lateinit var transformer: Transformer
            lateinit var progressRunnable: Runnable

            val videoEffects = buildList<Effect> {
                if (probe != null && probe.shortSide > TARGET_SHORT_SIDE_PX) {
                    add(Presentation.createForShortSide(TARGET_SHORT_SIDE_PX))
                }
                if (probe != null && probe.frameRate > 35) {
                    add(FrameDropEffect.createDefaultFrameDropEffect(TARGET_MAX_FPS))
                }
            }

            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
            val editedBuilder = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
            if (probe != null && !probe.hasAudio) {
                editedBuilder.setRemoveAudio(true)
            }
            val editedMediaItem = editedBuilder.build()

            val sequence = if (probe != null && !probe.hasAudio) {
                EditedMediaItemSequence.withVideoFrom(listOf(editedMediaItem))
            } else {
                EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
            }
            val composition = Composition.Builder(sequence)
                .setHdrMode(hdrMode)
                .build()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacks(progressRunnable)
                    onProgress(100)
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacks(progressRunnable)
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val transformerBuilder = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)

            if (useCustomEncoderSettings) {
                val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(TARGET_VIDEO_BITRATE)
                            .build()
                    )
                    .build()
                transformerBuilder.setEncoderFactory(encoderFactory)
            }

            transformer = transformerBuilder.build()

            val progressHolder = ProgressHolder()
            var lastReported = -1
            progressRunnable = object : Runnable {
                override fun run() {
                    if (finished) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val pct = progressHolder.progress.coerceIn(0, 99)
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(pct)
                        }
                    }
                    if (!finished && state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            }

            continuation.invokeOnCancellation {
                finished = true
                mainHandler.removeCallbacks(progressRunnable)
                runCatching { transformer.cancel() }
            }

            try {
                transformer.start(composition, outputFile.absolutePath)
                mainHandler.postDelayed(progressRunnable, 200L)
            } catch (_: Exception) {
                finished = true
                mainHandler.removeCallbacks(progressRunnable)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
}
