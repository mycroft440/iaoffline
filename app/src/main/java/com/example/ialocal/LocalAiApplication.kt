package com.example.ialocal

import android.app.Application

class LocalAiApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
