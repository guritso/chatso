package com.example

import android.app.Application
import android.content.Context
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.data.LocalIdentityStore
import com.example.data.MediaStorageHelper
import com.example.network.ChatsoNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ChatsoContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: ChatDatabase = ChatDatabase.getInstance(context)
    val identityStore = LocalIdentityStore(context)
    val mediaStorage = MediaStorageHelper(context)
    val repository = ChatRepository(
        context = context,
        scope = appScope,
        chatDao = database.chatDao(),
        identityStore = identityStore,
        mediaStorage = mediaStorage
    )

    @Volatile
    var isAppInForeground: Boolean = false

    init {
        ChatsoNotificationHelper.ensureChannels(context)
        repository.onIncomingNotification = { peerId, senderName, preview ->
            val activeOpen = repository.currentlyOpenPeerId
            if (!isAppInForeground || activeOpen != peerId) {
                ChatsoNotificationHelper.showIncomingMessageNotification(
                    context = context,
                    peerId = peerId,
                    senderName = senderName,
                    preview = preview
                )
            }
        }
    }
}

class ChatsoApplication : Application() {
    val container: ChatsoContainer by lazy { ChatsoContainer(this) }
}
