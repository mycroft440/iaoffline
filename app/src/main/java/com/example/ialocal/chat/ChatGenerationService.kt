package com.example.ialocal.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.example.ialocal.LocalAiApplication
import com.example.ialocal.MainActivity
import com.example.ialocal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground and the CPU awake while [ChatGenerationManager] is producing
 * an answer, so switching apps or turning the screen off does not pause or kill the inference.
 * The service stops itself as soon as no conversation is generating.
 */
class ChatGenerationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Respostas da I.A", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantém a I.A respondendo quando o app está em segundo plano."
                setShowBadge(false)
            }
        )
        // Android gives a started foreground service only a short window to publish its notification.
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IAOffline:ChatGeneration")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService call must be answered with startForeground.
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val manager = (application as LocalAiApplication).container.chatGenerationManager
        if (intent?.action == ACTION_STOP) manager.stopAll()
        if (!observing) {
            observing = true
            serviceScope.launch {
                manager.activeGenerations.collect { active ->
                    // stopSelfResult keeps the service alive if a newer start arrived meanwhile.
                    if (active <= 0 && stopSelfResult(lastStartId)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        // An answer cannot be resumed after the process dies, so there is nothing to redeliver.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ChatGenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("I.A Offline")
            .setContentText("Gerando resposta…")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(Notification.Action.Builder(R.drawable.ic_download_notification, "Parar", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chat_generation"
        private const val NOTIFICATION_ID = 4401
        private const val REQUEST_OPEN_APP = 4402
        private const val REQUEST_STOP = 4403
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        private const val ACTION_STOP = "com.example.ialocal.action.STOP_CHAT_GENERATION"

        /** Called while the user is interacting with the chat, so the foreground start is allowed. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ChatGenerationService::class.java))
            }
        }
    }
}
