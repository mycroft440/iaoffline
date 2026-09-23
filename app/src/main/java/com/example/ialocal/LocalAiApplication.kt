package com.example.ialocal

import android.app.Application
import com.example.ialocal.diagnostics.LastExitReporter
import com.example.ialocal.models.PublicModelDownloads

class LocalAiApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        LastExitReporter(this).installUncaughtExceptionRecorder()
        Thread(
            {
                runCatching { PublicModelDownloads(this).ensureFolder() }
            },
            "ia-offline-public-downloads",
        ).start()
    }
}
