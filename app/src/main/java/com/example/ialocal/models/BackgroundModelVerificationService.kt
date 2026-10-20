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
import android.os.PowerManager
import com.example.ialocal.LocalAiApplication
import com.example.ialocal.MainActivity
import com.example.ialocal.R
import com.example.ialocal.data.ModelVerificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tests GGUFs found in shared storage in the background, one at a time, using real inference.
 * Catalog downloads are already tested by ModelDownloadService; this service covers models found
 * by the automatic scanner and tests interrupted when an Android process restarts.
 */
class BackgroundModelVerificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var testJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewal: Job? = null
    private lateinit var notifications: NotificationManager
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Teste das IAs instaladas", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            testJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notification("Verificando IAs instaladas…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (testJob?.isActive != true) {
            testJob = scope.launch {
                try {
                    val container = (application as LocalAiApplication).container
                    while (isActive) {
                        val id = pending().firstOrNull() ?: break
                        val model = container.modelRepository.getModel(id)
                        if (model == null || model.verificationStatus == ModelVerificationStatus.VERIFIED.name ||
                            model.verificationStatus == ModelVerificationStatus.ERROR.name) {
                            remove(id)
                            continue
                        }
                        // Never interrupt an active answer or another download's own automatic
                        // verification. Both share the same native inference runtime.
                        while (isActive && (container.modelManager.downloadState.value.isBusy ||
                            container.chatGenerationManager.activeGenerations.value > 0)) {
                            delay(5_000)
                        }
                        if (!isActive) break
                        val previousAttempts = prefs.getInt("attempt_$id", 0)
                        if (previousAttempts >= 2) {
                            container.modelRepository.markVerificationError(
                                id, "O Android interrompeu testes anteriores. Refaça o teste manualmente.",
                            )
                            remove(id)
                            continue
                        }
                        prefs.edit().putInt("attempt_$id", previousAttempts + 1).apply()
                        notifications.notify(ID, notification("Testando ${model.name} em segundo plano…"))
                        keepCpuAwakeDuringTest()
                        try {
                            container.modelManager.verifyAndActivate(id)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            // ModelManager already saves ERROR and keeps the installed GGUF.
                        } finally {
                            releaseCpuGuard()
                        }
                        remove(id)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } finally {
                    testJob = null
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep the CPU awake for actual inference, not while waiting for other active operations. */
    private fun keepCpuAwakeDuringTest() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IAOffline:VerifyImportedModel")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire(15L * 60_000L)
        if (wakeRenewal?.isActive != true) {
            wakeRenewal = scope.launch {
                while (isActive) {
                    delay(5L * 60_000L)
                    if (lock.isHeld) lock.release()
                    lock.acquire(15L * 60_000L)
                }
            }
        }
    }

    private fun releaseCpuGuard() {
        wakeRenewal?.cancel()
        wakeRenewal = null
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        testJob?.cancel()
        releaseCpuGuard()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 4612,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 4613,
            Intent(this, BackgroundModelVerificationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("Verificação automática de I.A")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(Notification.Action.Builder(R.drawable.ic_download_notification, "Pausar testes", pause).build())
            .build()
    }

    private fun pending(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private fun remove(id: String) {
        synchronized(PENDING_LOCK) {
            prefs.edit().putStringSet(KEY, pending() - id).commit()
            prefs.edit().remove("attempt_$id").apply()
        }
    }

    companion object {
        private const val PREFS = "automatic_model_verification"
        private const val KEY = "pending_ids"
        private const val CHANNEL = "model_verification"
        private const val ID = 4611
        private const val ACTION_STOP = "com.example.ialocal.action.PAUSE_MODEL_TESTS"
        private val PENDING_LOCK = Any()

        fun enqueue(context: Context, modelId: String) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(PENDING_LOCK) {
                val queued = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() + modelId
                prefs.edit().putStringSet(KEY, queued).commit()
            }
            resumePending(context)
        }

        fun resumePending(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getStringSet(KEY, emptySet()).isNullOrEmpty()) return
            runCatching {
                context.startForegroundService(Intent(context, BackgroundModelVerificationService::class.java))
            }
            // If Android disallows background FGS launch, the persisted queue resumes on next open.
        }
    }
}
