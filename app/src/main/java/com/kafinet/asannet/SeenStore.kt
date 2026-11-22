package com.kafinet.asannet

import android.content.Context

/**
 * ردیابی «دیده‌شدن» محتوای تازه روی خود گوشی: برای هر دسته (روی آیکون صفحه‌ی اصلی)
 * و هر پوشه‌ی داخل یک دسته، آخرین باری که کاربر بازش کرده ذخیره می‌شود؛ اگر از آن
 * موقع چیز تازه‌تری اضافه شده باشد (بر اساس addedAt/episodeAt آیتم‌ها)، همان‌جا یک
 * دایره‌ی قرمز نشان داده می‌شود تا کاربر بفهمد مورد جدیدی هست.
 */
object SeenStore {
    private const val PREFS = "kafinet_seen"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** آخرین زمانی که این کلید (دسته یا پوشه) باز شده؛ صفر یعنی هیچ‌وقت. */
    private fun lastSeen(context: Context, key: String): Long =
        prefs(context).getLong(key, 0L)

    /** این کلید را «دیده‌شده تا همین لحظه» علامت می‌زند. */
    fun markSeenNow(context: Context, key: String) {
        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }

    /** آیا جدیدترین موردِ این گروه، بعد از آخرین باری که کاربر بازش کرده اضافه شده؟ */
    fun hasUnseen(context: Context, key: String, newestTimestamp: Long): Boolean =
        newestTimestamp > 0L && newestTimestamp > lastSeen(context, key)

    fun categoryKey(typeKey: String): String = "cat_$typeKey"
    fun folderKey(typeKey: String, folderPath: String): String = "sec_${typeKey}_$folderPath"
}
