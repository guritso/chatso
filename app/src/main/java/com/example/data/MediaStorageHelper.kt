package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CopiedMediaInfo(
    val localFile: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)

class MediaStorageHelper(private val context: Context) {
    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }

    private val avatarsDir: File
        get() = File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }

    suspend fun loadBitmapForCrop(sourceUri: Uri, maxSidePx: Int = 1280): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
                val w = boundsOpts.outWidth
                val h = boundsOpts.outHeight
                if (w <= 0 || h <= 0) return@withContext null

                var sampleSize = 1
                while (w / sampleSize > maxSidePx * 2 || h / sampleSize > maxSidePx * 2) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val raw = cr.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: return@withContext null

                val orientation = cr.openInputStream(sourceUri)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL

                val matrix = android.graphics.Matrix()
                when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                    android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                }
                val rotated = if (!matrix.isIdentity) {
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                        if (it !== raw) raw.recycle()
                    }
                } else {
                    raw
                }
                val maxDim = maxOf(rotated.width, rotated.height)
                if (maxDim > maxSidePx) {
                    val scale = maxSidePx.toFloat() / maxDim.toFloat()
                    val nw = (rotated.width * scale).toInt().coerceAtLeast(1)
                    val nh = (rotated.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(rotated, nw, nh, true).also {
                        if (it !== rotated) rotated.recycle()
                    }
                } else {
                    rotated
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun saveProfileAvatar(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val decoded = loadBitmapForCrop(sourceUri, 512) ?: return@withContext null
            val scaled = scaleCenterSquare(decoded, 256)
            val destFile = File(avatarsDir, "local_avatar_${System.currentTimeMillis()}.jpg")
            FileOutputStream(destFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            // Clean up older local avatar files
            avatarsDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("local_avatar_") && f.absolutePath != destFile.absolutePath) {
                    f.delete()
                }
            }
            destFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    suspend fun savePeerAvatarBase64(peerId: String, avatarBase64: String?): String? =
        withContext(Dispatchers.IO) {
            if (avatarBase64.isNullOrBlank()) return@withContext null
            try {
                val bytes = Base64.decode(avatarBase64, Base64.NO_WRAP)
                val destFile = File(avatarsDir, "peer_${peerId}.jpg")
                destFile.writeBytes(bytes)
                destFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }

    suspend fun encodeAvatarBase64(avatarPath: String?): String? = withContext(Dispatchers.IO) {
        if (avatarPath.isNullOrBlank()) return@withContext null
        val file = File(avatarPath)
        if (!file.exists() || file.length() > 250_000L) return@withContext null
        try {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            val thumb = scaleCenterSquare(decoded, 128)
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun copyUriToPrivateMedia(uri: Uri, fallbackPrefix: String): CopiedMediaInfo? =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val mimeType = cr.getType(uri) ?: "application/octet-stream"
                var displayName = resolveDisplayName(uri)
                if (displayName.isNullOrBlank()) {
                    val ext = mimeType.substringAfterLast('/', "bin").take(5)
                    displayName = "${fallbackPrefix}_${System.currentTimeMillis()}.$ext"
                }
                val safeName = sanitizeFileName(displayName)
                val targetFile = File(mediaDir, "${UUID.randomUUID()}_$safeName")
                cr.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                } ?: return@withContext null
                CopiedMediaInfo(
                    localFile = targetFile,
                    fileName = displayName,
                    mimeType = mimeType,
                    sizeBytes = targetFile.length()
                )
            } catch (_: Exception) {
                null
            }
        }

    fun createIncomingMediaFile(messageId: String, originalFileName: String): File {
        val safeName = sanitizeFileName(originalFileName.ifBlank { "arquivo.bin" })
        return File(mediaDir, "${messageId}_$safeName")
    }

    fun createNewVoiceRecordingFile(): File {
        return File(mediaDir, "voz_${System.currentTimeMillis()}.m4a")
    }

    fun readAudioDurationMs(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            dur
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun deleteMediaFile(localPath: String?) = withContext(Dispatchers.IO) {
        if (localPath.isNullOrBlank()) return@withContext
        try {
            val file = File(localPath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun openOrShareFile(localPath: String, mimeType: String?) {
        try {
            val file = File(localPath)
            if (!file.exists()) return
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, file.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(80)
    }

    private fun scaleCenterSquare(src: Bitmap, targetPx: Int): Bitmap {
        val minSide = minOf(src.width, src.height)
        val x = (src.width - minSide) / 2
        val y = (src.height - minSide) / 2
        val cropped = Bitmap.createBitmap(src, x, y, minSide, minSide)
        return Bitmap.createScaledBitmap(cropped, targetPx, targetPx, true)
    }
}
