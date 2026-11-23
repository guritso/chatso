package com.example.network

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat

data class RawShareRequest(
    val targetPeerId: String?,
    val sharedText: String?,
    val streamUris: List<Uri>
)

object ShareIntentParser {
    const val EXTRA_SHARE_CONSUMED = "com.example.extra.SHARE_CONSUMED"

    fun parse(intent: Intent?): RawShareRequest? {
        if (intent == null) return null
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        if (intent.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return null

        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        val targetPeerId = ChatsoSharingShortcuts.peerIdFromShortcutId(shortcutId)

        val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val extraSubject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val clipTexts = mutableListOf<String>()
        val collectedUris = LinkedHashSet<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { collectedUris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { uri -> if (uri != null) collectedUris.add(uri) }
            }
        }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                item.uri?.let { collectedUris.add(it) }
                if (extraText == null) {
                    item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        clipTexts.add(it)
                    }
                }
            }
        }

        val resolvedText = when {
            extraText != null && extraSubject != null && !extraText.contains(extraSubject) ->
                "$extraSubject\n$extraText"
            extraText != null -> extraText
            extraSubject != null -> extraSubject
            clipTexts.isNotEmpty() -> clipTexts.distinct().joinToString("\n")
            else -> null
        }

        if (resolvedText == null && collectedUris.isEmpty()) return null

        return RawShareRequest(
            targetPeerId = targetPeerId,
            sharedText = resolvedText,
            streamUris = collectedUris.toList()
        )
    }

    fun markConsumed(intent: Intent?) {
        intent?.putExtra(EXTRA_SHARE_CONSUMED, true)
    }
}
