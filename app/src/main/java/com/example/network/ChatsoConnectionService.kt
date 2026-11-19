package com.example.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.ChatsoApplication
import com.example.MainActivity
import com.example.R
import com.example.data.LocaleHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object ChatsoNotificationHelper {
    const val CHANNEL_CONNECTION = "chatso_connection_status"
    const val CHANNEL_MESSAGES = "chatso_incoming_messages"
    const val NOTIFICATION_ID_SERVICE = 1001
    const val EXTRA_PEER_ID = "extra_peer_id"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val ACTION_MARK_READ = "com.example.action.NOTIF_MARK_READ"
    const val ACTION_REPLY = "com.example.action.NOTIF_REPLY"
    const val ACTION_DISMISS = "com.example.action.NOTIF_DISMISS"
    const val KEY_TEXT_REPLY = "key_text_reply"
    private const val BRAND_TEAL = 0xFF0F766E.toInt()
    private const val MAX_CACHED_MESSAGES = 8

    private data class CachedBubbleLine(
        val text: CharSequence,
        val timestamp: Long,
        val isFromLocalUser: Boolean
    )

    private data class ConversationNotificationCache(
        val peerId: String,
        val senderName: String,
        val avatarPath: String?,
        val localUsername: String,
        val lines: MutableList<CachedBubbleLine> = mutableListOf()
    )

    private val conversationCaches = ConcurrentHashMap<Int, ConversationNotificationCache>()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val localized = LocaleHelper.wrapContext(context)

        val statusChannel = NotificationChannel(
            CHANNEL_CONNECTION,
            localized.getString(R.string.notif_channel_connection),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            localized.getString(R.string.notif_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
        }

        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(messagesChannel)
    }

    fun buildServiceNotification(context: Context, connectedCount: Int): Notification {
        ensureChannels(context)
        val localized = LocaleHelper.wrapContext(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (connectedCount > 0) {
            localized.getString(R.string.notif_service_connected, connectedCount)
        } else {
            localized.getString(R.string.notif_service_listening)
        }

        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_chatso)
            .setColor(BRAND_TEAL)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    fun showIncomingMessageNotification(
        context: Context,
        peerId: String,
        senderName: String,
        preview: String,
        avatarPath: String? = null,
        localUsername: String = "You",
        notificationId: Int = peerId.hashCode()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val cache = conversationCaches.compute(notificationId) { _, existing ->
            val current = existing?.takeIf { it.peerId == peerId } ?: ConversationNotificationCache(
                peerId = peerId,
                senderName = senderName,
                avatarPath = avatarPath,
                localUsername = localUsername
            )
            current.lines.add(
                CachedBubbleLine(
                    text = preview,
                    timestamp = System.currentTimeMillis(),
                    isFromLocalUser = false
                )
            )
            while (current.lines.size > MAX_CACHED_MESSAGES) {
                current.lines.removeAt(0)
            }
            current.copy(
                senderName = senderName,
                avatarPath = avatarPath ?: current.avatarPath,
                localUsername = localUsername
            )
        } ?: return

        postConversationNotification(
            context = context,
            cache = cache,
            notificationId = notificationId,
            onlyAlertOnce = false
        )
    }

    fun appendLocalReplyToNotification(
        context: Context,
        peerId: String,
        replyText: String,
        notificationId: Int = peerId.hashCode()
    ) {
        val existing = conversationCaches[notificationId]?.takeIf { it.peerId == peerId } ?: return
        existing.lines.add(
            CachedBubbleLine(
                text = replyText,
                timestamp = System.currentTimeMillis(),
                isFromLocalUser = true
            )
        )
        while (existing.lines.size > MAX_CACHED_MESSAGES) {
            existing.lines.removeAt(0)
        }
        postConversationNotification(
            context = context,
            cache = existing,
            notificationId = notificationId,
            onlyAlertOnce = true
        )
    }

    fun cancelPeerNotification(
        context: Context,
        peerId: String,
        notificationId: Int = peerId.hashCode()
    ) {
        conversationCaches.remove(notificationId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(notificationId)
    }

    internal fun clearCachedNotificationOnly(notificationId: Int) {
        conversationCaches.remove(notificationId)
    }

    fun showIncomingReactionNotification(
        context: Context,
        peerId: String,
        messageId: String,
        senderName: String,
        emoji: String,
        reactedSnippet: String,
        avatarPath: String? = null,
        localUsername: String = "You"
    ) {
        val localized = LocaleHelper.wrapContext(context)
        val body = localized.getString(R.string.notif_reacted_preview, emoji, reactedSnippet)
        showIncomingMessageNotification(
            context = context,
            peerId = peerId,
            senderName = senderName,
            preview = body,
            avatarPath = avatarPath,
            localUsername = localUsername,
            notificationId = ("reaction_${peerId}_$messageId").hashCode()
        )
    }

    private fun postConversationNotification(
        context: Context,
        cache: ConversationNotificationCache,
        notificationId: Int,
        onlyAlertOnce: Boolean
    ) {
        ensureChannels(context)
        val localized = LocaleHelper.wrapContext(context)
        val peerId = cache.peerId
        val shortcutId = "conv_$peerId"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER_ID, peerId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val cleanAvatarBitmap = buildNotificationAvatarBitmap(
            context = context,
            senderName = cache.senderName,
            avatarPath = cache.avatarPath,
            includeCornerBadge = !isAndroid11Plus
        )
        val personIcon = IconCompat.createWithBitmap(cleanAvatarBitmap)

        val localUserPerson = Person.Builder()
            .setName(cache.localUsername.ifBlank { "You" })
            .setKey("chatso_local_user")
            .build()

        val senderPerson = Person.Builder()
            .setName(cache.senderName)
            .setKey(peerId)
            .setIcon(personIcon)
            .build()

        // Publish a long-lived conversation shortcut so SystemUI (Android 11+) renders the
        // WhatsApp-style conversation header (large contact avatar + mini Chatso corner icon,
        // without the redundant top "Chatso" app title row).
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(cache.senderName.ifBlank { "Chatso" })
                .setLongLived(true)
                .setPerson(senderPerson)
                .setCategories(setOf("android.shortcut.conversation"))
                .setIcon(personIcon)
                .setIntent(openIntent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }

        val style = NotificationCompat.MessagingStyle(localUserPerson)
        cache.lines.forEach { line ->
            style.addMessage(
                line.text,
                line.timestamp,
                if (line.isFromLocalUser) null else senderPerson
            )
        }

        val markReadIntent = Intent(context, ChatsoNotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPi = PendingIntent.getBroadcast(
            context,
            notificationId * 31 + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val replyIntent = Intent(context, ChatsoNotificationActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(
            context,
            notificationId * 31 + 2,
            replyIntent,
            replyFlags
        )

        val dismissIntent = Intent(context, ChatsoNotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            context,
            notificationId * 31 + 3,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(localized.getString(R.string.notif_reply_hint))
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            0,
            localized.getString(R.string.notif_action_reply),
            replyPi
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()

        val markReadAction = NotificationCompat.Action.Builder(
            0,
            localized.getString(R.string.notif_action_mark_read),
            markReadPi
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val latestLine = cache.lines.lastOrNull()?.text ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_chatso)
            .setColor(BRAND_TEAL)
            .setLargeIcon(cleanAvatarBitmap)
            .setContentTitle(cache.senderName)
            .setContentText(latestLine)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(senderPerson)
            .setShowWhen(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPi)
            .setDeleteIntent(dismissPi)
            .addAction(markReadAction)
            .addAction(replyAction)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(notificationId, notification)
    }

    private fun buildNotificationAvatarBitmap(
        context: Context,
        senderName: String,
        avatarPath: String?,
        includeCornerBadge: Boolean
    ): Bitmap {
        val sizePx = 256
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        var drewPhoto = false
        if (!avatarPath.isNullOrBlank()) {
            val file = File(avatarPath)
            if (file.exists()) {
                val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (decoded != null) {
                    val minSide = minOf(decoded.width, decoded.height)
                    val x = (decoded.width - minSide) / 2
                    val y = (decoded.height - minSide) / 2
                    val square = Bitmap.createBitmap(decoded, x, y, minSide, minSide)
                    val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
                    val circleBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val circleCanvas = Canvas(circleBitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    val rect = Rect(0, 0, sizePx, sizePx)
                    circleCanvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    circleCanvas.drawBitmap(scaled, rect, rect, paint)
                    canvas.drawBitmap(circleBitmap, 0f, 0f, null)
                    circleBitmap.recycle()
                    if (square !== decoded) square.recycle()
                    if (scaled !== square) scaled.recycle()
                    decoded.recycle()
                    drewPhoto = true
                }
            }
        }

        if (!drewPhoto) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND_TEAL }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bgPaint)

            val initials = senderName.trim()
                .split(" ", "_", "-")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifBlank { "?" }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 94f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val fm = textPaint.fontMetrics
            val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(initials, sizePx / 2f, baseline, textPaint)
        }

        if (includeCornerBadge) {
            // Draw small circular Chatso icon badge on bottom-right corner on pre-Android 11 devices
            val badgeRadius = sizePx * 0.19f
            val cx = sizePx - badgeRadius - 4f
            val cy = sizePx - badgeRadius - 4f
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
            }
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = BRAND_TEAL
            }
            canvas.drawCircle(cx, cy, badgeRadius + 5f, borderPaint)
            canvas.drawCircle(cx, cy, badgeRadius, badgeBgPaint)

            val badgeDrawable = runCatching {
                ContextCompat.getDrawable(context, R.drawable.img_launcher_icon_1794975444462)
                    ?: ContextCompat.getDrawable(context, R.drawable.ic_notification_chatso)
            }.getOrNull()
            if (badgeDrawable != null) {
                val inset = (badgeRadius * 0.14f).toInt()
                val clipPath = android.graphics.Path().apply {
                    addCircle(cx, cy, badgeRadius, android.graphics.Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(clipPath)
                badgeDrawable.setBounds(
                    (cx - badgeRadius).toInt() + inset,
                    (cy - badgeRadius).toInt() + inset,
                    (cx + badgeRadius).toInt() - inset,
                    (cy + badgeRadius).toInt() - inset
                )
                badgeDrawable.draw(canvas)
                canvas.restore()
            }
        }

        return output
    }
}

class ChatsoNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(ChatsoNotificationHelper.EXTRA_PEER_ID) ?: return
        val defaultId = peerId.hashCode()
        val notificationId = intent.getIntExtra(ChatsoNotificationHelper.EXTRA_NOTIFICATION_ID, defaultId)

        when (intent.action) {
            ChatsoNotificationHelper.ACTION_DISMISS -> {
                ChatsoNotificationHelper.clearCachedNotificationOnly(notificationId)
            }
            ChatsoNotificationHelper.ACTION_MARK_READ -> {
                val pendingResult = goAsync()
                val app = context.applicationContext as? ChatsoApplication
                if (app == null) {
                    ChatsoNotificationHelper.cancelPeerNotification(context, peerId, notificationId)
                    pendingResult.finish()
                    return
                }
                app.container.appScope.launch {
                    try {
                        app.container.repository.markConversationRead(peerId, fromNotification = true)
                        ChatsoNotificationHelper.cancelPeerNotification(context, peerId, notificationId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ChatsoNotificationHelper.ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ChatsoNotificationHelper.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()
                if (replyText.isNullOrBlank()) return

                val pendingResult = goAsync()
                val app = context.applicationContext as? ChatsoApplication
                if (app == null) {
                    pendingResult.finish()
                    return
                }
                app.container.appScope.launch {
                    try {
                        val repo = app.container.repository
                        repo.markConversationRead(peerId, fromNotification = true)
                        repo.sendText(peerId, replyText)
                        ChatsoNotificationHelper.appendLocalReplyToNotification(
                            context = context,
                            peerId = peerId,
                            replyText = replyText,
                            notificationId = notificationId
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

class ChatsoConnectionService : Service() {

    companion object {
        fun syncServiceState(context: Context, shouldRun: Boolean) {
            val intent = Intent(context, ChatsoConnectionService::class.java)
            try {
                if (shouldRun) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var keepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground(0)
        val container = (application as ChatsoApplication).container

        monitorJob = serviceScope.launch {
            container.repository.nodeManager.connectedPeers.collectLatest { connected ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(
                    ChatsoNotificationHelper.NOTIFICATION_ID_SERVICE,
                    ChatsoNotificationHelper.buildServiceNotification(
                        applicationContext,
                        connected.size
                    )
                )
            }
        }

        keepAliveJob = serviceScope.launch {
            while (isActive) {
                delay(25_000L)
                val id = container.identityStore.getSnapshot()
                if (id.isInitialized) {
                    container.repository.nodeManager.updateIdentityAndEnsureServer(id)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as ChatsoApplication).container
        val connectedCount = container.repository.nodeManager.connectedPeers.value.size
        startAsForeground(connectedCount)
        return START_STICKY
    }

    private fun startAsForeground(connectedCount: Int) {
        val notification = ChatsoNotificationHelper.buildServiceNotification(
            applicationContext,
            connectedCount
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                ChatsoNotificationHelper.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(ChatsoNotificationHelper.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        keepAliveJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
