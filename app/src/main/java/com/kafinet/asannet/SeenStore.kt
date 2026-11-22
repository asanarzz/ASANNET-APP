package com.kafinet.asannet

import android.content.Context

/**
 * ردیابی «دیده‌شدن» محتوای تازه روی خود گوشی، به‌ازای تک‌تک آیتم‌ها (نه فقط به‌ازای
 * یک بار باز کردن پوشه): شناسه‌ی هر آیتمی که کاربر واقعاً بازش کرده، همین‌جا ذخیره
 * می‌شود. تا وقتی همه‌ی آیتم‌های تازه‌ی یک پوشه/دسته یکی‌یکی باز نشده باشند، دایره‌ی
 * قرمزِ همان پوشه/دسته (و البته خودِ همان آیتم) باقی می‌ماند.
 */
object SeenStore {
    private const val PREFS = "kafinet_seen"
    private const val KEY_SEEN_IDS = "seen_item_ids"
    private const val KEY_BASELINE_DONE = "baseline_done"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun seenIds(context: Context): MutableSet<String> =
        HashSet(prefs(context).getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty())

    /** آیا کاربر این آیتم رو باز کرده (دیده)؟ */
    fun isItemSeen(context: Context, id: String): Boolean = seenIds(context).contains(id)

    /** این آیتم رو «دیده‌شده» علامت می‌زنه (وقتی کاربر واقعاً بازش می‌کنه). */
    fun markItemSeen(context: Context, id: String) {
        val set = seenIds(context)
        if (set.add(id)) {
            prefs(context).edit().putStringSet(KEY_SEEN_IDS, set).apply()
        }
    }

    /**
     * اولین باری که این قابلیت رو برنامه داره (روی این گوشی)، همه‌ی آیتم‌های موجودِ الان
     * رو «دیده‌شده» حساب می‌کنه؛ وگرنه کل محتوای قبلیِ برنامه یهو دایره‌ی قرمز می‌گرفت.
     * از این به بعد، فقط آیتم‌هایی که واقعاً تازه اضافه می‌شن جزو «ندیده» حساب می‌شن.
     */
    fun ensureBaseline(context: Context, allIds: Collection<String>) {
        val p = prefs(context)
        if (p.getBoolean(KEY_BASELINE_DONE, false)) return
        val set = seenIds(context)
        set.addAll(allIds)
        p.edit().putStringSet(KEY_SEEN_IDS, set).putBoolean(KEY_BASELINE_DONE, true).apply()
    }

    /** آیا بین این گروه از شناسه‌ها، حداقل یکی هست که هنوز باز نشده؟ */
    fun hasUnseen(context: Context, ids: Collection<String>): Boolean {
        if (ids.isEmpty()) return false
        val seen = seenIds(context)
        return ids.any { it !in seen }
    }
}
