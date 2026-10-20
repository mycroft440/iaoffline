package com.example.ialocal.api

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
import com.example.ialocal.R
import com.example.ialocal.chat.ResponseNotificationRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The online response stays in the application-owned controller after its UI is left.
 * This separate foreground notification returns to the exact API conversation.
 */
class RemoteApiResponseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewal: Job? = null
    private var conversationId: String = ""

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Respostas por API", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Acompanhe respostas das IAs conectadas pela internet."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as LocalAiApplication).container.externalApiViewModel.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        conversationId = intent?.getStringExtra(EXTRA_ID)
            ?: (application as LocalAiApplication).container.externalApiViewModel.currentId.value.orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IAOffline:RemoteApiResponse")
                .apply { setReferenceCounted(false) }
        }
        val lock = wakeLock ?: return START_NOT_STICKY
        if (!lock.isHeld) lock.acquire(WAKE_LEASE_MS)
        if (renewal?.isActive != true) {
            renewal = scope.launch {
                while (isActive) {
                    delay(WAKE_RENEW_MS)
                    if (lock.isHeld) lock.release()
                    lock.acquire(WAKE_LEASE_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        renewal?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 4502,
            ResponseNotificationRoute.openConversation(this, ResponseNotificationRoute.ONLINE, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 4503,
            Intent(this, RemoteApiResponseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("I.A por API respondendo")
            .setContentText("Toque para abrir esta conversa e acompanhar a resposta.")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(Notification.Action.Builder(R.drawable.ic_download_notification, "Parar", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remote_api_responses"
        private const val NOTIFICATION_ID = 4501
        private const val ACTION_STOP = "com.example.ialocal.action.STOP_REMOTE_ANSWER"
        private const val EXTRA_ID = "remote_conversation_id"
        private const val WAKE_LEASE_MS = 15L * 60_000L
        private const val WAKE_RENEW_MS = 5L * 60_000L

        fun start(context: Context, conversationId: String) {
            if (conversationId.isBlank()) return
            runCatching {
                context.startForegroundService(
                    Intent(context, RemoteApiResponseService::class.java)
                        .putExtra(EXTRA_ID, conversationId)
                )
            }
        }

        fun finish(context: Context) {
            context.stopService(Intent(context, RemoteApiResponseService::class.java))
        }
    }
}
