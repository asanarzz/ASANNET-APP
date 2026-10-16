package com.kafinet.asannet

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.MediaController
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

/**
 * کنترل پخش (MediaController) که دکمه‌ی برگشت گوشی را قورت نمی‌دهد:
 * در MediaController معمولی، اگر کنترل‌ها روی صفحه باشند، اولین برگشت فقط آن‌ها را می‌بندد.
 * اینجا برگشت همیشه مستقیم پخش‌کننده را می‌بندد.
 */
class PlayerMediaController(context: Context) : MediaController(context) {

    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                onBack?.invoke()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
