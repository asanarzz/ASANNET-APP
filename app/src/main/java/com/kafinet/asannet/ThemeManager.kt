package com.kafinet.asannet

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** ذخیره و اعمال حالت تاریک/روشن برنامه، مستقل از تنظیمات سیستم گوشی. */
object ThemeManager {
    private const val PREFS_NAME = "kafinet_theme"
    private const val KEY_DARK_MODE = "dark_mode"

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
        applyNightMode(enabled)
    }

    /** در ابتدای اجرای برنامه (Application.onCreate) صدا زده می‌شود. */
    fun applySavedTheme(context: Context) {
        applyNightMode(isDarkMode(context))
    }

    private fun applyNightMode(enabled: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
