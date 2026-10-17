package com.example.ialocal

import android.app.Application
import com.example.ialocal.diagnostics.LastExitReporter
import com.example.ialocal.models.PublicModelDownloads
import com.example.ialocal.runtime.DeviceMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalAiApplication : Application() {
    private val containerDelegate = lazy { AppContainer(this) }
    val container: AppContainer by containerDelegate
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    }

    /**
     * In the background, a loaded model makes this app the first one Android closes when memory
     * runs out, and squeezes the app the user is in. When memory is tight the idle model is freed
     * instead; the next message loads it again. A model that is loading or answering stays.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_BACKGROUND || !containerDelegate.isInitialized()) return
        val memory = DeviceMemory.read(this)
        @Suppress("DEPRECATION") // Still delivered on Android 13, which the app supports.
        val deepInBackground = level >= TRIM_MEMORY_MODERATE
        if (!deepInBackground && memory.availableBytes >= memory.totalBytes * TIGHT_MEMORY_FRACTION) return
        memoryScope.launch { container.modelRuntime.releaseIfIdle() }
    }

    private companion object {
        /** Below this share of free RAM, Android is about to close background apps. */
        const val TIGHT_MEMORY_FRACTION = 0.20
    }
}
