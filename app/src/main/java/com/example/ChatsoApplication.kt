package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.network.ChatsoNotificationHelper
import com.example.network.ChatsoSharingShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ChatsoContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: ChatDatabase = ChatDatabase.getInstance(context)
    val identityStore = LocalIdentityStore(context)
    val mediaStorage = MediaStorageHelper(context)

    @Volatile
    var isAppInForeground: Boolean = false

    val repository = ChatRepository(
        context = context,
        scope = appScope,
        chatDao = database.chatDao(),
        identityStore = identityStore,
        mediaStorage = mediaStorage,
        isAppInForegroundProvider = { isAppInForeground }
    )

    init {
        ChatsoNotificationHelper.ensureChannels(context)
        appScope.launch {
            identityStore.identityFlow.collect { id ->
                ChatsoNotificationHelper.syncThemeConfig(context, id.themePalette, id.themeAdvanced)
            }
        }
        appScope.launch {
            repository.allPeersFlow
                .distinctUntilChanged { old, new ->
                    old.size == new.size && old.zip(new).all { (a, b) ->
                        a.peerId == b.peerId &&
                            a.username == b.username &&
                            a.avatarPath == b.avatarPath &&
                            a.lastActivityAt == b.lastActivityAt
                    }
                }
                .collect { peers ->
                    runCatching {
                        ChatsoSharingShortcuts.syncContactShortcuts(context, peers)
                    }
                }
        }
        repository.onClearPeerNotification = { peerId ->
            ChatsoNotificationHelper.cancelPeerNotification(context, peerId)
        }
        repository.onIncomingNotification = { peerId, senderName, preview, avatarPath ->
            val activeOpen = repository.currentlyOpenPeerId
            if (!isAppInForeground || activeOpen != peerId) {
                appScope.launch {
                    runCatching {
                        val localName = identityStore.getSnapshot().username.ifBlank { "You" }
                        ChatsoNotificationHelper.showIncomingMessageNotification(
                            context = context,
                            peerId = peerId,
                            senderName = senderName,
                            preview = preview,
                            avatarPath = avatarPath,
                            localUsername = localName
                        )
                    }
                }
            }
        }
        repository.onIncomingReactionNotification =
            { peerId, messageId, senderName, emoji, snippet, avatarPath ->
                val activeOpen = repository.currentlyOpenPeerId
                if (!isAppInForeground || activeOpen != peerId) {
                    appScope.launch {
                        runCatching {
                            val localName = identityStore.getSnapshot().username.ifBlank { "You" }
                            ChatsoNotificationHelper.showIncomingReactionNotification(
                                context = context,
                                peerId = peerId,
                                messageId = messageId,
                                senderName = senderName,
                                emoji = emoji,
                                reactedSnippet = snippet,
                                avatarPath = avatarPath,
                                localUsername = localName
                            )
                        }
                    }
                }
            }
    }
}

class ChatsoApplication : Application(), ImageLoaderFactory {
    val container: ChatsoContainer by lazy { ChatsoContainer(this) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(false)
            .build()
    }
}
