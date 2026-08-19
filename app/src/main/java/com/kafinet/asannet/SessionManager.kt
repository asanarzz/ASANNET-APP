package com.kafinet.asannet

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "kafinet_session"
    private const val KEY_REGISTERED = "is_registered"

    fun isRegistered(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGISTERED, false)
    }

    fun setRegistered(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }
}
