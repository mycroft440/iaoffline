package com.example.ialocal.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.example.ialocal.LocalAiApplication
import com.example.ialocal.MainActivity
import com.example.ialocal.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a catalog model transfer alive when the UI is backgrounded or its ViewModel is destroyed.
 * The transfer is owned by this foreground service, not by a screen lifecycle.
 */
class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: ModelManager
    private lateinit var notificationManager: NotificationManager

    private var activeJob: Job? = null
    private var notificationJob: Job? = null
    private var activeCatalogId: String? = null
    private val userStopInProgress = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val app = application as LocalAiApplication
        manager = app.container.modelManager
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        notificationJob = serviceScope.launch {
            manager.downloadState.collectLatest { state ->
                val catalogId = activeCatalogId
                if (catalogId != null && state.catalogId == catalogId) {
                    val ongoing = activeJob?.isActive == true || state.isBusy
                    runCatching {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, ongoing = ongoing))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val catalogId = intent?.getStringExtra(EXTRA_CATALOG_ID)
                    ?: activeCatalogId
                    ?: return START_NOT_STICKY
                startDownload(catalogId)
            }
            ACTION_PAUSE -> pauseDownload()
            ACTION_END -> endDownload(intent.getStringExtra(EXTRA_CATALOG_ID))
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the app from Recents must not cancel a user-started model transfer.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(catalogId: String) {
        if (activeJob?.isActive == true) return

        val catalog = runCatching { ModelCatalog.requireById(catalogId) }.getOrElse {
            stopSelf()
            return
        }
        activeCatalogId = catalogId
        userStopInProgress.set(false)

        val initial = manager.downloadState.value.takeIf { it.catalogId == catalogId }
            ?: ModelDownloadState(
                catalogId = catalogId,
                phase = ModelDownloadPhase.CHECKING,
                totalBytes = catalog.approximateSizeBytes,
                message = "Preparando download em segundo plano…",
            )

        startForeground(
            NOTIFICATION_ID,
            buildNotification(initial, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        activeJob = serviceScope.launch {
            try {
                while (isActive) {
                    try {
                        manager.downloadAndVerify(catalogId)
                        break
                    } catch (offline: NetworkUnavailableException) {
                        if (!isActive) throw offline
                        // Keep ownership while offline. The partial file is preserved and the next
                        // attempt resumes with HTTP Range instead of requiring another user action.
                        delay(NETWORK_RETRY_DELAY_MS)
                        manager.prepareNetworkRetry(catalogId)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                val stoppedByUser = userStopInProgress.get()
                activeJob = null
                if (!stoppedByUser) {
                    withContext(Dispatchers.Main) {
                        val finalState = manager.downloadState.value
                        runCatching {
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildNotification(finalState, ongoing = false),
                            )
                        }
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun pauseDownload() {
        if (!userStopInProgress.compareAndSet(false, true)) return
        val job = activeJob
        job?.cancel(CancellationException("Download pausado pelo usuário."))
        serviceScope.launch {
            job?.join()
            manager.markDownloadPausedByUser(activeCatalogId ?: manager.downloadState.value.catalogId)
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun endDownload(requestedCatalogId: String?) {
        if (!userStopInProgress.compareAndSet(false, true)) return
        val catalogId = activeCatalogId ?: requestedCatalogId ?: manager.downloadState.value.catalogId
        val job = activeJob
        job?.cancel(CancellationException("Download encerrado pelo usuário."))
        serviceScope.launch {
            job?.join()
            catalogId?.let { manager.discardDownload(it) }
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads de I.A",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantém downloads de modelos de I.A ativos em segundo plano."
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ModelDownloadState, ongoing: Boolean): Notification {
        val catalog = state.catalogId?.let { id -> runCatching { ModelCatalog.requireById(id) }.getOrNull() }
        val title = catalog?.displayName?.let { "I.A Offline · $it" } ?: "I.A Offline · Download"
        val text = state.message ?: phaseText(state.phase)

        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_PAUSE,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endIntent = PendingIntent.getService(
            this,
            REQUEST_END,
            Intent(this, ModelDownloadService::class.java)
                .setAction(ACTION_END)
                .putExtra(EXTRA_CATALOG_ID, state.catalogId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (ongoing) {
            builder.addAction(Notification.Action.Builder(R.drawable.app_launcher, "Pausar", pauseIntent).build())
            builder.addAction(Notification.Action.Builder(R.drawable.app_launcher, "Encerrar", endIntent).build())
        }

        when {
            state.phase == ModelDownloadPhase.DOWNLOADING && state.progress != null -> {
                builder.setProgress(100, (state.progress * 100f).roundToInt().coerceIn(0, 100), false)
            }
            ongoing -> builder.setProgress(0, 0, true)
            else -> builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun phaseText(phase: ModelDownloadPhase): String = when (phase) {
        ModelDownloadPhase.IDLE -> "Pronto"
        ModelDownloadPhase.CHECKING -> "Preparando download…"
        ModelDownloadPhase.DOWNLOADING -> "Baixando modelo…"
        ModelDownloadPhase.VERIFYING_FILE -> "Verificando arquivo…"
        ModelDownloadPhase.IMPORTING -> "Registrando modelo…"
        ModelDownloadPhase.VERIFYING_MODEL -> "Testando modelo no aparelho…"
        ModelDownloadPhase.COMPLETE -> "Instalação concluída."
        ModelDownloadPhase.ERROR -> "O download encontrou um erro."
        ModelDownloadPhase.CANCELLED -> "Download pausado."
    }

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4301
        private const val REQUEST_OPEN_APP = 4302
        private const val REQUEST_PAUSE = 4303
        private const val REQUEST_END = 4304
        private const val NETWORK_RETRY_DELAY_MS = 5_000L

        const val ACTION_START = "com.example.ialocal.action.START_MODEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.example.ialocal.action.PAUSE_MODEL_DOWNLOAD"
        const val ACTION_END = "com.example.ialocal.action.END_MODEL_DOWNLOAD"
        const val EXTRA_CATALOG_ID = "catalog_id"

        fun start(context: Context, catalogId: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CATALOG_ID, catalogId)
            context.startForegroundService(intent)
        }

        fun pause(context: Context, catalogId: String?) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_CATALOG_ID, catalogId)
            context.startService(intent)
        }

        fun end(context: Context, catalogId: String?) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_END)
                .putExtra(EXTRA_CATALOG_ID, catalogId)
            context.startService(intent)
        }
    }
}
