package com.example.network

import com.example.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val htmlUrl: String
    ) : UpdateCheckResult()

    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/guritso/chatso/releases/latest"

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME
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
            parseReleaseJson(body, currentVersion)
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    fun parseReleaseJson(jsonText: String, currentVersion: String): UpdateCheckResult {
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
        var apkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name", "")
                val dl = asset.optString("browser_download_url", "")
                if (dl.isNotBlank() && assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = dl
                    break
                }
            }
        }
        return if (isRemoteVersionNewer(cleanRemote, currentVersion)) {
            UpdateCheckResult.UpdateAvailable(
                latestVersion = cleanRemote,
                releaseNotes = notes,
                downloadUrl = apkUrl ?: htmlUrl,
                htmlUrl = htmlUrl
            )
        } else {
            UpdateCheckResult.UpToDate(normalizeVersion(currentVersion).ifBlank { currentVersion })
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
