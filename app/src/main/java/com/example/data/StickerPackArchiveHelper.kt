package com.example.data

import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StickerPackProbe(
    val sourcePath: String,
    val suggestedName: String,
    val author: String,
    val stickerCount: Int,
    val previewStickers: List<ByteArray> = emptyList()
)

data class ImportedStickerPackResult(
    val pack: StickerPackEntity,
    val stickers: List<PackStickerEntity>
)

private data class CachedProbeEntry(
    val probe: StickerPackProbe?
)

private data class ParsedZipPackContents(
    val suggestedName: String,
    val author: String,
    val trayEntry: ZipImageEntry?,
    val stickerEntries: List<ZipImageEntry>
)

private data class ZipImageEntry(
    val entryPath: String,
    val fileName: String,
    val mimeType: String,
    val extension: String,
    val bytes: ByteArray,
    val sha256: String
)

object StickerPackArchiveHelper {
    private const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 10 * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    private const val MAX_STICKERS_PER_PACK = 150
    private const val MAX_METADATA_BYTES = 256 * 1024
    private const val PREVIEW_LIMIT = 16

    private val probeCache = LruCache<String, CachedProbeEntry>(64)

    fun hasPotentialStickerPackExtension(fileName: String?): Boolean {
        val lower = fileName?.lowercase()?.trim() ?: return false
        return lower.endsWith(".enc") ||
            lower.endsWith(".wastickers") ||
            lower.endsWith(".zip")
    }

    fun getCachedProbe(filePath: String?): StickerPackProbe? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        val key = cacheKey(file)
        return probeCache.get(key)?.probe
    }

    suspend fun probePackArchive(
        file: File,
        displayNameHint: String? = null,
        includePreviews: Boolean = false
    ): StickerPackProbe? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() !in 32L..MAX_ARCHIVE_BYTES) {
            return@withContext null
        }
        val key = cacheKey(file)
        val cached = probeCache.get(key)
        if (cached != null) {
            val cachedProbe = cached.probe ?: return@withContext null
            if (!includePreviews || cachedProbe.previewStickers.isNotEmpty()) {
                return@withContext cachedProbe
            }
        }

        if (!isZipMagicHeader(file)) {
            probeCache.put(key, CachedProbeEntry(null))
            return@withContext null
        }

        val parsed = runCatching {
            parseArchive(
                file = file,
                displayNameHint = displayNameHint,
                maxImageEntries = MAX_STICKERS_PER_PACK
            )
        }.getOrNull()

        if (parsed == null || parsed.stickerEntries.isEmpty()) {
            probeCache.put(key, CachedProbeEntry(null))
            return@withContext null
        }

        val previews = if (includePreviews) {
            parsed.stickerEntries.take(PREVIEW_LIMIT).map { it.bytes }
        } else {
            emptyList()
        }

        val probe = StickerPackProbe(
            sourcePath = file.absolutePath,
            suggestedName = parsed.suggestedName,
            author = parsed.author,
            stickerCount = parsed.stickerEntries.size,
            previewStickers = previews
        )
        probeCache.put(key, CachedProbeEntry(probe))
        probe
    }

    suspend fun importPackToDirectory(
        file: File,
        stickerPacksRootDir: File,
        packNameOverride: String? = null,
        displayNameHint: String? = null
    ): ImportedStickerPackResult? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() !in 32L..MAX_ARCHIVE_BYTES) {
            return@withContext null
        }
        if (!isZipMagicHeader(file)) return@withContext null

        val parsed = runCatching {
            parseArchive(
                file = file,
                displayNameHint = displayNameHint,
                maxImageEntries = MAX_STICKERS_PER_PACK
            )
        }.getOrNull() ?: return@withContext null

        if (parsed.stickerEntries.isEmpty()) return@withContext null

        val packId = UUID.randomUUID().toString()
        val packDir = File(stickerPacksRootDir, packId).apply { if (!exists()) mkdirs() }
        val now = System.currentTimeMillis()

        val savedStickers = mutableListOf<PackStickerEntity>()
        val seenHashes = HashSet<String>()

        try {
            parsed.stickerEntries.forEachIndexed { idx, entry ->
                if (!seenHashes.add(entry.sha256)) return@forEachIndexed
                val targetFile = File(
                    packDir,
                    "${idx.toString().padStart(3, '0')}_${entry.sha256.take(12)}.${entry.extension}"
                )
                FileOutputStream(targetFile).use { out ->
                    out.write(entry.bytes)
                }
                if (targetFile.exists() && targetFile.length() > 0L) {
                    savedStickers += PackStickerEntity(
                        stickerId = UUID.randomUUID().toString(),
                        packId = packId,
                        localPath = targetFile.absolutePath,
                        mimeType = entry.mimeType,
                        sha256 = entry.sha256,
                        sortOrder = savedStickers.size,
                        createdAt = now + savedStickers.size
                    )
                }
            }

            if (savedStickers.isEmpty()) {
                runCatching { packDir.deleteRecursively() }
                return@withContext null
            }

            var iconPath: String? = savedStickers.first().localPath
            val tray = parsed.trayEntry
            if (tray != null && tray.bytes.isNotEmpty()) {
                val trayFile = File(packDir, "tray_icon.${tray.extension}")
                runCatching {
                    FileOutputStream(trayFile).use { it.write(tray.bytes) }
                    if (trayFile.exists() && trayFile.length() > 0L) {
                        iconPath = trayFile.absolutePath
                    }
                }
            }

            val resolvedName = packNameOverride
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: parsed.suggestedName

            val packEntity = StickerPackEntity(
                packId = packId,
                name = resolvedName.take(64),
                author = parsed.author.take(64),
                iconPath = iconPath,
                createdAt = now
            )
            ImportedStickerPackResult(
                pack = packEntity,
                stickers = savedStickers
            )
        } catch (_: Exception) {
            runCatching { packDir.deleteRecursively() }
            null
        }
    }

    private fun parseArchive(
        file: File,
        displayNameHint: String?,
        maxImageEntries: Int
    ): ParsedZipPackContents? {
        var jsonTitle: String? = null
        var jsonAuthor: String? = null
        var jsonTrayName: String? = null
        val jsonStickerOrder = LinkedHashMap<String, Int>()
        var txtTitle: String? = null
        var txtAuthor: String? = null

        val allImages = mutableListOf<ZipImageEntry>()
        var totalBytesRead = 0L

        // ZipFile reads the central directory and supports archives with stored entries that
        // use a trailing data descriptor. ZipInputStream rejects those valid ZIPs.
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val rawPath = entry.name ?: continue
                if (entry.isDirectory) continue
                if (rawPath.contains("..") || rawPath.startsWith("/")) continue
                val normalizedPath = rawPath.replace('\\', '/')
                if (normalizedPath.startsWith("__MACOSX/") ||
                    normalizedPath.substringAfterLast('/').startsWith(".")
                ) {
                    continue
                }

                val baseName = normalizedPath.substringAfterLast('/')
                val lowerBase = baseName.lowercase()

                if (lowerBase in METADATA_JSON_NAMES || lowerBase == "title.txt" || lowerBase == "author.txt") {
                    val input = zip.getInputStream(entry)
                    val metaBytes = try {
                        readEntryLimited(input, MAX_METADATA_BYTES)
                    } finally {
                        input.close()
                    }
                    if (metaBytes != null) {
                        totalBytesRead += metaBytes.size
                        if (totalBytesRead > MAX_TOTAL_UNCOMPRESSED_BYTES) break
                        val text = metaBytes.toString(Charsets.UTF_8).trim()
                        when (lowerBase) {
                            "title.txt" -> txtTitle = text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                            "author.txt" -> txtAuthor = text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                            else -> parseMetadataJson(
                                jsonText = text,
                                onTitle = { if (jsonTitle.isNullOrBlank()) jsonTitle = it },
                                onAuthor = { if (jsonAuthor.isNullOrBlank()) jsonAuthor = it },
                                onTrayFile = { if (jsonTrayName.isNullOrBlank()) jsonTrayName = it },
                                stickerOrderOut = jsonStickerOrder
                            )
                        }
                    }
                    continue
                }

                if (allImages.size >= maxImageEntries + 4) {
                    // Still continue scanning metadata if needed, or stop once plenty collected
                    continue
                }

                val input = zip.getInputStream(entry)
                val bytes = try {
                    readEntryLimited(input, MAX_ENTRY_BYTES)
                } finally {
                    input.close()
                } ?: continue
                totalBytesRead += bytes.size
                if (totalBytesRead > MAX_TOTAL_UNCOMPRESSED_BYTES) break
                if (bytes.size < 12) continue

                val detected = detectImageType(lowerBase, bytes) ?: continue
                val sha256 = sha256Hex(bytes)
                allImages += ZipImageEntry(
                    entryPath = normalizedPath,
                    fileName = baseName,
                    mimeType = detected.first,
                    extension = detected.second,
                    bytes = bytes,
                    sha256 = sha256
                )
            }
        }

        if (allImages.isEmpty()) return null

        val trayCandidates = allImages.filter { entry ->
            val lowerFile = entry.fileName.lowercase()
            val nameNoExt = lowerFile.substringBeforeLast('.')
            (jsonTrayName != null && (lowerFile == jsonTrayName!!.lowercase() || entry.entryPath.equals(jsonTrayName, ignoreCase = true))) ||
                nameNoExt == "tray" ||
                nameNoExt == "tray_icon" ||
                nameNoExt == "trayicon"
        }

        val nonTrayImages = allImages.filterNot { it in trayCandidates }
        val webpStickers = nonTrayImages.filter { it.mimeType == "image/webp" }
        val stickerPool = when {
            webpStickers.isNotEmpty() -> webpStickers
            nonTrayImages.isNotEmpty() -> nonTrayImages
            else -> allImages
        }.take(maxImageEntries)

        val orderedStickers = if (jsonStickerOrder.isNotEmpty()) {
            stickerPool.sortedWith(
                compareBy<ZipImageEntry> { entry ->
                    jsonStickerOrder[entry.fileName.lowercase()]
                        ?: jsonStickerOrder[entry.entryPath.lowercase()]
                        ?: Int.MAX_VALUE
                }.thenBy { it.entryPath.lowercase() }
            )
        } else {
            stickerPool.sortedBy { it.entryPath.lowercase() }
        }

        val trayEntry = trayCandidates.firstOrNull()
        val folderTitle = orderedStickers
            .mapNotNull { it.entryPath.substringBeforeLast('/', "").takeIf { dir -> dir.isNotBlank() && !dir.contains('/') } }
            .distinct()
            .singleOrNull()

        val suggestedName = listOfNotNull(
            jsonTitle,
            txtTitle,
            folderTitle?.let(::cleanPackDisplayName),
            cleanPackDisplayName(displayNameHint ?: file.name)
        ).firstOrNull { it.isNotBlank() } ?: "Sticker Pack"

        val author = listOfNotNull(jsonAuthor, txtAuthor)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        return ParsedZipPackContents(
            suggestedName = suggestedName,
            author = author,
            trayEntry = trayEntry,
            stickerEntries = orderedStickers
        )
    }

    private fun parseMetadataJson(
        jsonText: String,
        onTitle: (String) -> Unit,
        onAuthor: (String) -> Unit,
        onTrayFile: (String) -> Unit,
        stickerOrderOut: MutableMap<String, Int>
    ) {
        runCatching {
            val root = JSONObject(jsonText)
            val packObj = when {
                root.has("sticker_packs") -> root.optJSONArray("sticker_packs")?.optJSONObject(0) ?: root
                else -> root
            }
            listOf("name", "title", "pack_name", "packName").forEach { key ->
                packObj.optString(key).trim().takeIf { it.isNotEmpty() }?.let(onTitle)
            }
            listOf("publisher", "author", "creator").forEach { key ->
                packObj.optString(key).trim().takeIf { it.isNotEmpty() }?.let(onAuthor)
            }
            listOf("tray_image_file", "trayImageFile", "tray_icon", "icon").forEach { key ->
                packObj.optString(key).trim().takeIf { it.isNotEmpty() }?.let(onTrayFile)
            }
            val arr: JSONArray? = packObj.optJSONArray("stickers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val fname = listOf("image_file", "imageFile", "file", "filename")
                        .firstNotNullOfOrNull { k -> item.optString(k).trim().takeIf { it.isNotEmpty() } }
                        ?: continue
                    stickerOrderOut[fname.lowercase()] = i
                }
            }
        }
    }

    private fun detectImageType(lowerFileName: String, bytes: ByteArray): Pair<String, String>? {
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp" to "webp"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png" to "png"
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return "image/gif" to "gif"
        }
        return when {
            lowerFileName.endsWith(".webp") -> "image/webp" to "webp"
            lowerFileName.endsWith(".png") -> "image/png" to "png"
            lowerFileName.endsWith(".gif") -> "image/gif" to "gif"
            else -> null
        }
    }

    private fun readEntryLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isZipMagicHeader(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                read == 4 &&
                    header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    (
                        (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                            (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
                        )
            }
        }.getOrDefault(false)
    }

    private fun cleanPackDisplayName(rawName: String): String {
        var name = rawName.substringAfterLast('/').trim()
        val uuidPrefix = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_(.+)$")
        uuidPrefix.matchEntire(name)?.let { name = it.groupValues[1] }
        val suffixes = listOf(".enc", ".wastickers", ".zip")
        var changed = true
        while (changed) {
            changed = false
            for (suffix in suffixes) {
                if (name.lowercase().endsWith(suffix)) {
                    name = name.dropLast(suffix.length)
                    changed = true
                }
            }
        }
        name = name.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return name
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheKey(file: File): String =
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    private val METADATA_JSON_NAMES = setOf(
        "contents.json",
        "metadata.json",
        "pack.json",
        "info.json",
        "manifest.json"
    )
}
