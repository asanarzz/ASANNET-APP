package com.kafinet.asannet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat

/** حلقه‌ی درصد مصرف حافظه (مثل کارت «حافظه‌ی داخلی» در صفحه‌ی اصلی مدیریت فایل). */
class StorageRingView(context: Context) : android.view.View(context) {

    private var percent = 0
    private val density = context.resources.displayMetrics.density
    private val rect = RectF()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        color = 0xFFE5E7EB.toInt()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15 * density
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.text_main)
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.yekan_boom)?.let { typeface = it }
    }

    fun setPercent(p: Int) {
        percent = p.coerceIn(0, 100)
        arc.color = if (percent >= 90) 0xFFDC2626.toInt() else 0xFF2563EB.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = track.strokeWidth / 2 + 1
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * percent / 100f, false, arc)
        val label = percent.toString() + "%"
        val y = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, width / 2f, y, text)
    }
}
