package com.kafinet.asannet

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "kafinet_session"
    private const val KEY_REGISTERED = "is_registered"
    private const val KEY_NATIONAL_CODE = "national_code"
    private const val KEY_FIRST_NAME = "first_name"
    private const val KEY_LAST_NAME = "last_name"

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

    /** نام و نام‌خانوادگی کاربر را برای نمایش در منوی کشویی روی همین گوشی ذخیره می‌کند. */
    fun saveUserName(context: Context, firstName: String, lastName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FIRST_NAME, firstName)
            .putString(KEY_LAST_NAME, lastName)
            .apply()
    }

    /** نام کامل ذخیره‌شده (نام + نام‌خانوادگی)، یا null اگر هنوز ذخیره نشده باشد. */
    fun getFullName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val first = prefs.getString(KEY_FIRST_NAME, null)
        val last = prefs.getString(KEY_LAST_NAME, null)
        val full = listOfNotNull(first, last).joinToString(" ").trim()
        return full.ifBlank { null }
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
