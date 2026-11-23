package com.example.network

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.data.PeerEntity
import com.example.ui.theme.AppIconManager

object ChatsoSharingShortcuts {
    const val SHARE_TARGET_CATEGORY = "com.example.category.SHARE_TARGET"
    const val CONVERSATION_CATEGORY = "android.shortcut.conversation"

    @Volatile
    private var lastSyncedPeers: List<PeerEntity> = emptyList()

    fun shortcutIdForPeer(peerId: String): String = "conv_$peerId"

    fun peerIdFromShortcutId(shortcutId: String?): String? {
        if (shortcutId.isNullOrBlank() || !shortcutId.startsWith("conv_")) return null
        return shortcutId.removePrefix("conv_").takeIf { it.isNotBlank() }
    }

    fun buildConversationShortcut(
        context: Context,
        peerId: String,
        displayName: String,
        avatarPath: String?,
        rank: Int = 0,
        precomputedPerson: Person? = null,
        precomputedIcon: IconCompat? = null
    ): ShortcutInfoCompat {
        val safeName = displayName.ifBlank { peerId.take(10) }
        val icon = precomputedIcon ?: IconCompat.createWithBitmap(
            ChatsoNotificationHelper.buildNotificationAvatarBitmap(
                context = context,
                senderName = safeName,
                avatarPath = avatarPath,
                includeCornerBadge = false
            )
        )
        val person = precomputedPerson ?: Person.Builder()
            .setName(safeName)
            .setKey(peerId)
            .setIcon(icon)
            .build()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatsoNotificationHelper.EXTRA_PEER_ID, peerId)
        }

        val builder = ShortcutInfoCompat.Builder(context, shortcutIdForPeer(peerId))
            .setShortLabel(safeName)
            .setLongLabel(safeName)
            .setLongLived(true)
            .setRank(rank.coerceAtLeast(0))
            .setPerson(person)
            .setCategories(setOf(CONVERSATION_CATEGORY, SHARE_TARGET_CATEGORY))
            .setIcon(icon)
            .setIntent(openIntent)
            .addCapabilityBinding("actions.intent.SEND_MESSAGE")

        AppIconManager.getActiveLauncherComponent(context)?.let { launcherComp ->
            builder.setActivity(launcherComp)
        }
        return builder.build()
    }

    fun syncContactShortcuts(context: Context, peers: List<PeerEntity>) {
        lastSyncedPeers = peers
        runCatching {
            val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                .coerceIn(1, 12)
            val ordered = peers
                .sortedByDescending { it.lastActivityAt }
                .take(maxCount)

            val shortcuts = ordered.mapIndexed { index, peer ->
                buildConversationShortcut(
                    context = context,
                    peerId = peer.peerId,
                    displayName = peer.username,
                    avatarPath = peer.avatarPath,
                    rank = index
                )
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            if (shortcuts.isNotEmpty() &&
                ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).isNotEmpty()
            ) {
                ShortcutManagerCompat.updateShortcuts(context, shortcuts)
            }
        }
    }

    fun resyncLastKnownShortcuts(context: Context) {
        if (lastSyncedPeers.isNotEmpty()) {
            syncContactShortcuts(context, lastSyncedPeers)
        }
    }

    fun pushContactShortcut(
        context: Context,
        peerId: String,
        displayName: String,
        avatarPath: String?
    ) {
        runCatching {
            val shortcut = buildConversationShortcut(
                context = context,
                peerId = peerId,
                displayName = displayName,
                avatarPath = avatarPath,
                rank = 0
            )
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun removeContactShortcut(context: Context, peerId: String) {
        lastSyncedPeers = lastSyncedPeers.filterNot { it.peerId == peerId }
        val id = shortcutIdForPeer(peerId)
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), null)
        }
    }
}
