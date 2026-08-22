package com.kafinet.asannet

import android.app.Application

class KafinetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedTheme(this)
    }
}
