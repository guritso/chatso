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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.ChatsoApplication
import com.example.MainActivity
import com.example.R
import com.example.data.LocaleHelper
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        preview: String
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
            peerId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(peerId.hashCode(), notification)
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

        // Ensure local TCP server stays alive and silently keep recent peer links healthy
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
