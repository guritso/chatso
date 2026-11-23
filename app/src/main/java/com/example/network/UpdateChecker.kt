package com.example.network

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val htmlUrl: String,
        val targetAbi: String = "",
        val assetName: String = ""
    ) : UpdateCheckResult()

    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/guritso/chatso/releases/latest"

    const val ABI_ARM64_V8A = "arm64-v8a"
    const val ABI_ARMEABI_V7A = "armeabi-v7a"
    const val ABI_X86_64 = "x86_64"

    private val SUPPORTED_RELEASE_ABIS = listOf(ABI_ARM64_V8A, ABI_ARMEABI_V7A, ABI_X86_64)

    fun detectInstalledAbi(context: Context? = null): String {
        val nativeLibDir = runCatching { context?.applicationInfo?.nativeLibraryDir }.getOrNull()
        val is64Bit = runCatching { Process.is64Bit() }.getOrNull()
        val osArch = runCatching { System.getProperty("os.arch") }.getOrNull()
        return detectDeviceAbi(
            supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray(),
            is64Bit = is64Bit,
            nativeLibraryDir = nativeLibDir,
            osArch = osArch
        )
    }

    fun detectDeviceAbi(
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
        is64Bit: Boolean? = null,
        nativeLibraryDir: String? = null,
        osArch: String? = null
    ): String {
        // 1. Inspect the nativeLibraryDir actually unpacked/mounted for the installed APK
        val libLeaf = nativeLibraryDir
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it).name.lowercase() }
            .orEmpty()
        when (libLeaf) {
            "arm64", "arm64-v8a" -> return ABI_ARM64_V8A
            "arm", "armeabi-v7a", "armeabi" -> return ABI_ARMEABI_V7A
            "x86_64" -> return ABI_X86_64
        }

        // 2. Check the running process bitness + device ABIs so a v7 APK installed on an arm64
        //    phone still selects armeabi-v7a instead of upgrading across ABIs unexpectedly.
        val normalizedSupported = supportedAbis.map { it.trim().lowercase() }
        if (is64Bit == false) {
            if (normalizedSupported.any { it.contains("arm") }) return ABI_ARMEABI_V7A
        } else if (is64Bit == true) {
            if (normalizedSupported.contains(ABI_X86_64)) return ABI_X86_64
            if (normalizedSupported.contains(ABI_ARM64_V8A)) return ABI_ARM64_V8A
        }

        // 3. Check process CPU architecture
        val arch = osArch?.trim()?.lowercase().orEmpty()
        when {
            arch == "aarch64" || arch == "arm64" -> return ABI_ARM64_V8A
            arch.startsWith("armv7") || arch == "arm" -> return ABI_ARMEABI_V7A
            arch == "x86_64" || arch == "amd64" -> {
                if (normalizedSupported.isEmpty() || normalizedSupported.contains(ABI_X86_64)) {
                    return ABI_X86_64
                }
            }
        }

        // 4. Fallback to device's ordered SUPPORTED_ABIS
        for (abi in normalizedSupported) {
            when (abi) {
                ABI_ARM64_V8A -> return ABI_ARM64_V8A
                ABI_ARMEABI_V7A -> return ABI_ARMEABI_V7A
                ABI_X86_64 -> return ABI_X86_64
            }
        }
        return ABI_ARM64_V8A
    }

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME,
        preferredAbi: String = detectInstalledAbi()
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Chatso-Android/$currentVersion")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Error("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseReleaseJson(body, currentVersion, preferredAbi)
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    fun parseReleaseJson(
        jsonText: String,
        currentVersion: String,
        preferredAbi: String = detectInstalledAbi()
    ): UpdateCheckResult {
        val json = JSONObject(jsonText)
        val rawTag = json.optString("tag_name").ifBlank { json.optString("name") }
        val cleanRemote = normalizeVersion(rawTag)
        if (cleanRemote.isBlank()) {
            return UpdateCheckResult.Error("Missing release tag")
        }
        val htmlUrl = json.optString(
            "html_url",
            "https://github.com/guritso/chatso/releases/latest"
        )
        val notes = json.optString("body", "").trim()
        val normalizedTargetAbi = normalizeAbiName(preferredAbi)
        val selectedAsset = selectBestApkAsset(json.optJSONArray("assets"), normalizedTargetAbi)
        return if (isRemoteVersionNewer(cleanRemote, currentVersion)) {
            UpdateCheckResult.UpdateAvailable(
                latestVersion = cleanRemote,
                releaseNotes = notes,
                downloadUrl = selectedAsset?.second ?: htmlUrl,
                htmlUrl = htmlUrl,
                targetAbi = normalizedTargetAbi,
                assetName = selectedAsset?.first.orEmpty()
            )
        } else {
            UpdateCheckResult.UpToDate(normalizeVersion(currentVersion).ifBlank { currentVersion })
        }
    }

    fun selectBestApkAsset(
        assets: JSONArray?,
        targetAbi: String
    ): Pair<String, String>? {
        if (assets == null) return null
        val normalizedTarget = normalizeAbiName(targetAbi)
        var bestScore = -1
        var bestMatch: Pair<String, String>? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val assetName = asset.optString("name", "").trim()
            val dl = asset.optString("browser_download_url", "").trim()
            if (dl.isBlank() || !assetName.endsWith(".apk", ignoreCase = true)) continue
            val score = scoreAssetForAbi(assetName, normalizedTarget)
            if (score > bestScore) {
                bestScore = score
                bestMatch = assetName to dl
            }
        }
        return if (bestScore > 0) bestMatch else null
    }

    private fun scoreAssetForAbi(assetName: String, targetAbi: String): Int {
        val lower = assetName.lowercase().removeSuffix(".apk")
        val tokens = lower.split('-', '_', '.', '+').filter { it.isNotEmpty() }

        val mentionsArm64 = lower.contains("arm64-v8a") ||
            lower.contains("arm64_v8a") ||
            tokens.any { it == "arm64" || it == "aarch64" || it == "v8a" || it == "64" }
        val mentionsArmV7 = lower.contains("armeabi-v7a") ||
            lower.contains("armeabi_v7a") ||
            tokens.any { it == "armv7a" || it == "armv7" || it == "armeabi" || it == "v7a" || it == "v7" }
        val mentionsX86_64 = lower.contains("x86_64") ||
            lower.contains("x86-64") ||
            tokens.any { it == "amd64" || it == "x86" }
        val mentionsUniversal = tokens.contains("universal") || tokens.contains("fat")
        val mentionsAnyKnownAbi = mentionsArm64 || mentionsArmV7 || mentionsX86_64

        return when (targetAbi) {
            ABI_ARM64_V8A -> when {
                lower.contains("arm64-v8a") || lower.contains("arm64_v8a") -> 4
                mentionsArm64 && !mentionsX86_64 && !mentionsArmV7 -> 3
                mentionsUniversal || !mentionsAnyKnownAbi -> 1
                else -> 0
            }
            ABI_ARMEABI_V7A -> when {
                lower.contains("armeabi-v7a") || lower.contains("armeabi_v7a") -> 4
                mentionsArmV7 && !mentionsArm64 && !mentionsX86_64 -> 3
                mentionsUniversal || !mentionsAnyKnownAbi -> 1
                else -> 0
            }
            ABI_X86_64 -> when {
                lower.contains("x86_64") || lower.contains("x86-64") -> 4
                mentionsX86_64 && !mentionsArm64 && !mentionsArmV7 -> 3
                mentionsUniversal || !mentionsAnyKnownAbi -> 1
                else -> 0
            }
            else -> if (mentionsUniversal || !mentionsAnyKnownAbi) 1 else 0
        }
    }

    private fun normalizeAbiName(raw: String): String {
        val clean = raw.trim().lowercase()
        return when {
            clean == ABI_ARM64_V8A || clean == "arm64" || clean == "64" || clean == "v8a" -> ABI_ARM64_V8A
            clean == ABI_ARMEABI_V7A || clean == "armv7" || clean == "v7" || clean == "v7a" || clean == "armeabi" -> ABI_ARMEABI_V7A
            clean == ABI_X86_64 || clean == "x86-64" || clean == "x86" || clean == "amd64" -> ABI_X86_64
            clean in SUPPORTED_RELEASE_ABIS -> clean
            else -> ABI_ARM64_V8A
        }
    }

    fun isRemoteVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseSemverParts(remoteVersion)
        val localParts = parseSemverParts(currentVersion)
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    private fun parseSemverParts(raw: String): List<Int> {
        val core = normalizeVersion(raw).substringBefore('-')
        if (core.isBlank()) return emptyList()
        return core.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
