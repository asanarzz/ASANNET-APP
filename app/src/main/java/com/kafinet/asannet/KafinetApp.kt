package com.kafinet.asannet

import android.app.Application

class KafinetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // پروکسی باید قبل از هر درخواست شبکه‌ای آماده شود
        ProxyManager.init(this)
        ThemeManager.applySavedTheme(this)
        NotificationHelper.createChannel(this)
        ContentCheckWorker.schedule(this)
    }
}
