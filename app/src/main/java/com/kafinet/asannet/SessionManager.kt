package com.kafinet.asannet

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "kafinet_session"
    private const val KEY_REGISTERED = "is_registered"
    private const val KEY_NATIONAL_CODE = "national_code"

    fun isRegistered(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGISTERED, false)
    }

    fun setRegistered(context: Context, nationalCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REGISTERED, true)
            .putString(KEY_NATIONAL_CODE, nationalCode)
            .apply()
    }

    fun getNationalCode(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NATIONAL_CODE, null)
    }

    /** خروج از حساب — فقط اطلاعات محلی این گوشی پاک می‌شود؛ ثبت‌نام در Supabase باقی می‌ماند
     *  و کاربر می‌تواند بعداً دوباره با کد ملی و رمز عبورش وارد شود. */
    fun logout(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
