package com.example.ialocal

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.ialocal.data.KeepLoaded
import com.example.ialocal.diagnostics.LastExitReporter
import com.example.ialocal.models.PublicModelDownloads
import com.example.ialocal.runtime.DeviceMemory
import com.example.ialocal.runtime.MemorySnapshot
import com.example.ialocal.runtime.TuningSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocalAiApplication : Application() {
    private val containerDelegate = lazy { AppContainer(this) }
    val container: AppContainer by containerDelegate
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** When the app left the foreground; null while it is in it. */
    @Volatile private var backgroundSince: Long? = null
    /** Frees the idle AI when the time "Manter a IA carregada" grants it is over. */
    private var delayedRelease: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The ":ai" process only hosts the model (InferenceService); the app's setup is not for it.
        if (Application.getProcessName() != applicationInfo.processName) return
        LastExitReporter(this).installUncaughtExceptionRecorder()
        Thread(
            {
                runCatching { PublicModelDownloads(this).ensureFolder() }
            },
            "ia-offline-public-downloads",
        ).start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    backgroundSince = null
                    delayedRelease?.cancel()
                    if (shouldPreload()) container.chatGenerationManager.preload()
                }

                override fun onStop(owner: LifecycleOwner) {
                    backgroundSince = SystemClock.elapsedRealtime()
                    // Android may stop the AI process while the app is away: the conversation in its
                    // context is kept on storage now instead of after the idle delay.
                    if (containerDelegate.isInitialized()) memoryScope.launch { container.modelRuntime.saveSessionNow() }
                }
            },
        )
    }

    /** "Carregar a IA ao abrir o app": automatic from 12 GB of RAM. */
    private fun shouldPreload(): Boolean = when (container.performanceSettings.preload.value) {
        TuningSwitch.OFF -> false
        TuningSwitch.ON -> true
        TuningSwitch.AUTO -> DeviceMemory.read(this).advertisedGigabytes >= PRELOAD_MIN_GB
    }

    /**
     * In the background, a loaded model makes this app the first one Android closes when memory
     * runs out, and squeezes the app the user is in. When memory is tight the idle model is freed
     * instead; the next message loads it again, unless "Manter a IA carregada ao sair do app" keeps
     * it for a while. A model that is loading or answering stays.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_BACKGROUND || !containerDelegate.isInitialized()) return
        val memory = DeviceMemory.read(this)
        @Suppress("DEPRECATION") // Still delivered on Android 13, which the app supports.
        val deepInBackground = level >= TRIM_MEMORY_MODERATE
        if (!deepInBackground && memory.availableBytes >= memory.totalBytes * TIGHT_MEMORY_FRACTION) return
        when (keepLoaded(memory)) {
            KeepLoaded.UNTIL_ANDROID_NEEDS -> return
            KeepLoaded.TEN_MINUTES -> {
                val away = backgroundSince?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                val left = KEEP_LOADED_MS - away
                if (left > 0) {
                    // Best effort: the release runs late if Android freezes the app meanwhile.
                    delayedRelease?.cancel()
                    delayedRelease = memoryScope.launch {
                        delay(left)
                        if (backgroundSince != null) container.modelRuntime.releaseIfIdle()
                    }
                    return
                }
            }
            else -> Unit
        }
        memoryScope.launch { container.modelRuntime.releaseIfIdle() }
    }

    private fun keepLoaded(memory: MemorySnapshot): KeepLoaded = when (val choice = container.performanceSettings.keepLoaded.value) {
        KeepLoaded.AUTO -> if (memory.advertisedGigabytes >= PRELOAD_MIN_GB) KeepLoaded.TEN_MINUTES else KeepLoaded.RELEASE
        else -> choice
    }

    private companion object {
        /** Below this share of free RAM, Android is about to close background apps. */
        const val TIGHT_MEMORY_FRACTION = 0.20
        /** Phones from this RAM keep the AI loaded for a while and load it as the app opens. */
        const val PRELOAD_MIN_GB = 12.0
        const val KEEP_LOADED_MS = 10 * 60 * 1000L
    }
}
