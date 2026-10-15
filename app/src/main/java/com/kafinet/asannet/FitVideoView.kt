package com.kafinet.asannet

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView
import kotlin.math.max

/**
 * VideoView با دو حالت نمایش:
 * - حالت عادی: کل تصویر دیده می‌شود (ممکن است کنارش نوار سیاه بماند)
 * - حالت پر کردن صفحه (zoomToFill): تصویر بزرگ می‌شود تا کل صفحه را پر کند (لبه‌ها کمی بریده می‌شود)
 */
class FitVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {

    private var videoW = 0
    private var videoH = 0

    var zoomToFill: Boolean = false
        set(value) {
            field = value
            requestLayout()
        }

    fun setVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (zoomToFill && videoW > 0 && videoH > 0) {
            val cw = MeasureSpec.getSize(widthMeasureSpec)
            val ch = MeasureSpec.getSize(heightMeasureSpec)
            val scale = max(cw.toFloat() / videoW, ch.toFloat() / videoH)
            setMeasuredDimension((videoW * scale).toInt(), (videoH * scale).toInt())
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
