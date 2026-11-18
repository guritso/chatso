package com.example.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.ChatsoApplication
import com.example.MainActivity
import com.example.R
import com.example.data.LocaleHelper
import java.io.File
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
    private const val BRAND_TEAL = 0xFF0F766E.toInt()

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
        ensureChannels(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER_ID, peerId)
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val avatarBitmap = buildNotificationAvatarBitmap(context, senderName, avatarPath)
        val localUserPerson = Person.Builder()
            .setName(localUsername.ifBlank { "You" })
            .setKey("chatso_local_user")
            .build()
        val senderPerson = Person.Builder()
            .setName(senderName)
            .setKey(peerId)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap))
            .build()
        val style = NotificationCompat.MessagingStyle(localUserPerson)
            .addMessage(preview, System.currentTimeMillis(), senderPerson)

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_chatso)
            .setColor(BRAND_TEAL)
            .setLargeIcon(avatarBitmap)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setStyle(style)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(notificationId, notification)
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

    private fun buildNotificationAvatarBitmap(
        context: Context,
        senderName: String,
        avatarPath: String?
    ): Bitmap {
        val sizePx = 144
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
                textSize = 52f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val fm = textPaint.fontMetrics
            val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(initials, sizePx / 2f, baseline, textPaint)
        }

        // Draw small circular Chatso icon badge on bottom-right corner
        val badgeRadius = sizePx * 0.19f
        val cx = sizePx - badgeRadius - 3f
        val cy = sizePx - badgeRadius - 3f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND_TEAL
        }
        canvas.drawCircle(cx, cy, badgeRadius + 3.5f, borderPaint)
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

        return output
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
                    container.repository.nodeManager.ensureServerRunning(id)
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
