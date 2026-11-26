package com.kafinet.asannet

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * نوع نمایش فایل‌ها و پوشه‌ها (مثل ES File Explorer): آیکون / لیست / جزئیات، هرکدام بزرگ، متوسط یا کوچک.
 * پیش‌فرض: «جزئیات بزرگ». انتخاب کاربر ذخیره می‌شود و در مدیریت فایل و تب «پوشه‌ها»ی ارسال به تلویزیون مشترک است.
 */
object FileViewMode {
    const val ICON = 0
    const val LIST = 1
    const val DETAIL = 2

    const val LARGE = 0
    const val MEDIUM = 1
    const val SMALL = 2

    private const val PREFS = "kafinet_fm"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun style(c: Context): Int = prefs(c).getInt("view_style", DETAIL).coerceIn(ICON, DETAIL)
    fun scale(c: Context): Int = prefs(c).getInt("view_scale", LARGE).coerceIn(LARGE, SMALL)

    fun save(c: Context, style: Int, scale: Int) {
        prefs(c).edit().putInt("view_style", style).putInt("view_scale", scale).apply()
    }

    fun spanCount(scale: Int): Int = when (scale) {
        LARGE -> 3
        MEDIUM -> 4
        else -> 5
    }

    fun layoutManager(c: Context): RecyclerView.LayoutManager =
        if (style(c) == ICON) GridLayoutManager(c, spanCount(scale(c))) else LinearLayoutManager(c)

    /** پنجره‌ی انتخاب نوع نمایش (۹ حالت). بعد از انتخاب، onChanged صدا زده می‌شود. */
    fun showDialog(c: Context, onChanged: () -> Unit) {
        val styles = arrayOf(c.getString(R.string.fm_view_icon), c.getString(R.string.fm_view_list), c.getString(R.string.fm_view_detail))
        val scales = arrayOf(c.getString(R.string.fm_view_large), c.getString(R.string.fm_view_medium), c.getString(R.string.fm_view_small))
        val curStyle = style(c)
        val curScale = scale(c)
        val labels = ArrayList<String>()
        for (st in 0..2) for (sc in 0..2) {
            val mark = if (st == curStyle && sc == curScale) "✓  " else ""
            labels.add(mark + styles[st] + " " + scales[sc])
        }
        StyledDialog.Builder(c)
            .setTitle(R.string.fm_view_title)
            .setItems(labels.toTypedArray()) { _, which ->
                save(c, which / 3, which % 3)
                onChanged()
            }
            .setNegativeButton(R.string.fm_cancel, null)
            .show()
    }
}
