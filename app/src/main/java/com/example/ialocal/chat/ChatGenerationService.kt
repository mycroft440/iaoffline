package com.example.ialocal.chat

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
import android.os.IBinder
import android.os.PowerManager
import com.example.ialocal.LocalAiApplication
import com.example.ialocal.MainActivity
import com.example.ialocal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gives the app foreground priority and renews a partial CPU wake lock throughout generation.
 * Going Home, locking the screen or switching apps must not cancel ongoing/queued answers.
 * The OS can still stop a process for critical memory or an explicit user force-stop.
 */
class ChatGenerationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewalJob: Job? = null
    private var lastStartId = 0
    private var observing = false
    /** How many of the "loading" dots after "Pensando"/"Escrevendo" are shown: 1 to 3. */
    private var dots = 3

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Android gives a started foreground service only a short window to publish its notification.
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        // Acquire only after a generation is active. With a time-limited lease Android can
        // release it if our process dies; a renewal coroutine keeps long answers awake.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IAOffline:ChatGeneration")
            .apply { setReferenceCounted(false) }
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
                combine(manager.activeGenerations, manager.notificationConversationId, manager.notices) { active, id, _ ->
                    active to id
                }.collectLatest { (active, _) ->
                    // Rebuild contentIntent whenever the answering conversation changes. Once nothing
                    // is answering, the "Respondido" notification takes over; the ongoing one keeps
                    // its last text until it is removed below.
                    if (active > 0) {
                        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    }
                    if (ChatGenerationKeepAlive.needsCpuWakeLock(active)) {
                        keepCpuAwake()
                    } else {
                        releaseCpuGuard()
                        // Service.onStartCommand can run before the sender increments its active
                        // count. Give it time rather than immediately killing the foreground
                        // service with an initial flow value of zero.
                        delay(ChatGenerationKeepAlive.SERVICE_IDLE_GRACE_MS)
                        if (manager.activeGenerations.value == 0 && stopSelfResult(lastStartId)) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                }
            }
            // The dots after "Pensando"/"Escrevendo" fill up one by one, like a loading indicator.
            serviceScope.launch {
                val notificationManager = getSystemService(NotificationManager::class.java)
                while (isActive) {
                    delay(DOTS_INTERVAL_MS)
                    if (manager.activeGenerations.value == 0) continue
                    dots = dots % 3 + 1
                    notificationManager.notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
        // If Android kills the whole process, old coroutine/native state cannot be restored.
        // Never start an unrequested new response on a sticky service restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Foreground-service protection alone does not keep the CPU running during deep sleep.
     * Refresh the timed PARTIAL_WAKE_LOCK while active, including when the screen is off and
     * a reply takes longer than an hour. The lease expires safely if the service is killed.
     */
    private fun keepCpuAwake() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire(ChatGenerationKeepAlive.WAKE_LOCK_LEASE_MS)
        if (renewalJob?.isActive == true) return
        renewalJob = serviceScope.launch {
            while (isActive) {
                delay(ChatGenerationKeepAlive.WAKE_LOCK_RENEW_MS)
                // These changes run on the main dispatcher, serialized with releaseCpuGuard.
                if (lock.isHeld) lock.release()
                lock.acquire(ChatGenerationKeepAlive.WAKE_LOCK_LEASE_MS)
            }
        }
    }

    private fun releaseCpuGuard() {
        renewalJob?.cancel()
        renewalJob = null
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        releaseCpuGuard()
        serviceScope.cancel()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = (application as LocalAiApplication).container.chatGenerationManager
        val conversationId = manager.notificationConversationId.value
        val notice = conversationId?.let { manager.notices.value[it] }
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            ResponseNotificationRoute.openConversation(this, ResponseNotificationRoute.OFFLINE, conversationId.orEmpty()),
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
            .setContentTitle(answeringTitle(notice, dots))
            .setContentText("Toque para voltar à conversa e acompanhar a resposta.")
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
        private const val REQUEST_OPEN_ANSWER_BASE = 0x10000
        private const val ACTION_STOP = "com.example.ialocal.action.STOP_CHAT_GENERATION"
        /** "Respondido" notifications: one per conversation, tagged with its id. */
        private const val ANSWERED_NOTIFICATION_ID = 4404
        private const val DOTS_INTERVAL_MS = 500L

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Respostas da I.A", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mantém a I.A respondendo quando o app está em segundo plano e avisa quando ela terminar."
                    setShowBadge(false)
                }
            )
        }

        /** Replaces the ongoing notification's last step: "<modelo> - Respondido", tap to see the answer. */
        fun notifyAnswered(context: Context, conversationId: String, modelName: String?) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            runCatching {
                ensureChannel(context)
                val open = PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN_ANSWER_BASE + (conversationId.hashCode() and 0xFFFF),
                    ResponseNotificationRoute.openConversation(context, ResponseNotificationRoute.OFFLINE, conversationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download_notification)
                    .setContentTitle(answeredTitle(modelName))
                    .setContentText("Toque para visualizar a resposta.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .addAction(Notification.Action.Builder(R.drawable.ic_download_notification, "Visualizar", open).build())
                    .build()
                context.getSystemService(NotificationManager::class.java)
                    .notify(conversationId, ANSWERED_NOTIFICATION_ID, notification)
            }
        }

        fun cancelAnswered(context: Context, conversationId: String) {
            runCatching {
                context.getSystemService(NotificationManager::class.java).cancel(conversationId, ANSWERED_NOTIFICATION_ID)
            }
        }

        /** Called while the user is interacting with the chat, so the foreground start is allowed. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ChatGenerationService::class.java))
            }
        }
    }
}
