package com.kafinet.asannet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/* ===================================================================
 *  هسته‌ی ویرایش عکس (مشترک بین «ویرایش عکس» و «عکس پرسنلی»)
 *  مراحل ویرایش به‌صورت فهرست نگه داشته می‌شود و هم روی پیش‌نمایشِ کوچک و هم روی عکس اصلیِ
 *  باکیفیت، دقیقاً به یک شکل اجرا می‌شود (برش‌ها به‌صورت درصدی ذخیره می‌شوند).
 * =================================================================== */

/** یک مرحله‌ی ویرایش: چرخش ۹۰ درجه، آینه یا برش. */
class EditOp(val kind: Int, val rect: RectF?) {
    companion object {
        const val ROT_RIGHT = 1
        const val ROT_LEFT = 2
        const val FLIP_H = 3
        const val FLIP_V = 4
        const val CROP = 5
    }
}

object PhotoEditPipeline {

    /** بیشترین تعداد پیکسل بعد از چرخش با زاویه‌ی دلخواه (برای جلوگیری از پر شدن حافظه). */
    private const val MAX_ROTATED_PIXELS = 24000000L

    /**
     * چرخش با زاویه‌ی دلخواه (درجه، ساعت‌گرد). بوم بزرگ می‌شود تا هیچ جای عکس بریده نشود
     * و گوشه‌های خالی سفید می‌ماند.
     */
    fun rotateFree(src: Bitmap, degrees: Float): Bitmap {
        if (Math.abs(degrees) < 0.05f) return src
        val rad = Math.toRadians(degrees.toDouble())
        val sin = Math.abs(Math.sin(rad))
        val cos = Math.abs(Math.cos(rad))

        var source = src
        var w = src.width
        var h = src.height
        var nw = Math.max(1, Math.round(w * cos + h * sin).toInt())
        var nh = Math.max(1, Math.round(w * sin + h * cos).toInt())
        var shrunk: Bitmap? = null
        val area = nw.toLong() * nh.toLong()
        if (area > MAX_ROTATED_PIXELS) {
            val f = Math.sqrt(MAX_ROTATED_PIXELS.toDouble() / area.toDouble())
            val small = PhotoImageUtils.resizeSmooth(
                src,
                Math.max(1, (w * f).toInt()),
                Math.max(1, (h * f).toInt())
            )
            shrunk = small
            source = small
            w = small.width
            h = small.height
            nw = Math.max(1, Math.round(w * cos + h * sin).toInt())
            nh = Math.max(1, Math.round(w * sin + h * cos).toInt())
        }

        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(false)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-w / 2f, -h / 2f)
        m.postRotate(degrees)
        m.postTranslate(nw / 2f, nh / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, m, paint)
        if (shrunk != null && shrunk !== src) shrunk.recycle()
        return out
    }

    /** برش با مختصات درصدی (چپ، بالا، راست، پایین). */
    fun cropNormalized(src: Bitmap, r: RectF?): Bitmap {
        if (r == null) return src
        var x = Math.round(r.left * src.width)
        var y = Math.round(r.top * src.height)
        var w = Math.round((r.right - r.left) * src.width)
        var h = Math.round((r.bottom - r.top) * src.height)
        x = x.coerceIn(0, src.width - 1)
        y = y.coerceIn(0, src.height - 1)
        w = w.coerceIn(1, src.width - x)
        h = h.coerceIn(1, src.height - y)
        if (x == 0 && y == 0 && w == src.width && h == src.height) return src
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** اول زاویه‌ی دقیق، بعد مرحله‌ها به ترتیب. عکس ورودی هرگز recycle نمی‌شود. */
    fun apply(src: Bitmap, angle: Float, ops: List<EditOp>): Bitmap {
        var cur: Bitmap = src
        if (Math.abs(angle) >= 0.05f) {
            cur = rotateFree(cur, angle)
        }
        for (op in ops) {
            val next: Bitmap = when (op.kind) {
                EditOp.ROT_RIGHT -> PhotoImageUtils.transform(cur, 1, false, false)
                EditOp.ROT_LEFT -> PhotoImageUtils.transform(cur, 3, false, false)
                EditOp.FLIP_H -> PhotoImageUtils.transform(cur, 0, true, false)
                EditOp.FLIP_V -> PhotoImageUtils.transform(cur, 0, false, true)
                EditOp.CROP -> cropNormalized(cur, op.rect)
                else -> cur
            }
            if (next !== cur) {
                if (cur !== src) cur.recycle()
                cur = next
            }
        }
        return cur
    }

    /**
     * ناحیه‌ی r (درصدی از عکس؛ می‌تواند بیرون از ۰ تا ۱ هم باشد) را با اندازه‌ی دقیقِ outW×outH می‌کشد.
     * جاهایی که بیرون از عکس است با fillColor پر می‌شود. cm اختیاری برای نور و رنگ.
     */
    fun renderRegion(src: Bitmap, r: RectF, outW: Int, outH: Int, fillColor: Int, cm: ColorMatrix?): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(false)
        val canvas = Canvas(out)
        canvas.drawColor(fillColor or Color.BLACK)
        val srcRect = RectF(
            r.left * src.width,
            r.top * src.height,
            r.right * src.width,
            r.bottom * src.height
        )
        val m = Matrix()
        m.setRectToRect(srcRect, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), Matrix.ScaleToFit.FILL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (cm != null) paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    /**
     * تنظیم خودکار نور: روشن‌ترین و تیره‌ترین بخش عکس را به سفید و سیاه نزدیک می‌کند.
     * خروجی: [روشنایی، کنتراست] با همان مقیاسِ لغزنده‌ها (−۱۰۰ تا ۱۰۰).
     */
    fun autoLevels(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val step = Math.max(1, Math.max(w, h) / 200)
        val hist = IntArray(256)
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val l = (r * 299 + g * 587 + b * 114) / 1000
                hist[l]++
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return intArrayOf(0, 0)
        val lowTarget = count / 100
        val highTarget = count - count / 100
        var cum = 0
        var lo = 0
        var hi = 255
        var foundLo = false
        for (i in 0 until 256) {
            cum += hist[i]
            if (!foundLo && cum > lowTarget) {
                lo = i
                foundLo = true
            }
            if (cum >= highTarget) {
                hi = i
                break
            }
        }
        if (hi - lo < 30) return intArrayOf(0, 0)
        var scale = 255f / (hi - lo)
        if (scale < 0.7f) scale = 0.7f
        if (scale > 2f) scale = 2f
        val contrast = Math.round((scale - 1f) * 100f).coerceIn(-100, 100)
        val c2 = (100 + contrast) / 100f
        val t = -c2 * lo
        val brightness = Math.round((t - 128f * (1f - c2)) / 1.28f).coerceIn(-100, 100)
        return intArrayOf(brightness, contrast)
    }
}

/* ------------------------------------------------------------------
 *  بخش «چرخش و آینه» (مشترک): چرخش ۹۰ درجه به چپ و راست، آینه‌ی چپ‌وراست و بالا‌پایین،
 *  و یک لغزنده برای زاویه‌ی دقیق (راست کردنِ عکس کج).
 * ------------------------------------------------------------------ */
class PhotoRotatePanel(
    activity: PhotoToolActivity,
    parent: LinearLayout,
    private val onAction: (Int) -> Unit,
    private val onAngle: (Float) -> Unit
) {
    private val angleBar: SeekBar
    private val angleLabel: TextView
    private var angleValue = 0f

    init {
        val labels = arrayOf("چرخش ۹۰° به راست", "چرخش ۹۰° به چپ", "برعکس چپ و راست", "برعکس بالا و پایین")
        for (rowIndex in 0 until 2) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 2) {
                val i = rowIndex * 2 + col
                val b = PhotoUi.button(activity, labels[i], false) { onAction(i) }
                b.setPadding(PhotoUi.dp(activity, 6), PhotoUi.dp(activity, 10), PhotoUi.dp(activity, 6), PhotoUi.dp(activity, 10))
                val lp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
                if (col == 0) lp.marginEnd = PhotoUi.dp(activity, 8)
                row.addView(b, lp)
            }
            val rowLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            if (rowIndex == 1) rowLp.topMargin = PhotoUi.dp(activity, 8)
            parent.addView(row, rowLp)
        }

        angleLabel = PhotoUi.text(activity, "", 14f, PhotoUi.color(activity, R.color.text_muted), true)
        val labelLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        labelLp.topMargin = PhotoUi.dp(activity, 14)
        parent.addView(angleLabel, labelLp)
        angleBar = PhotoUi.seekBar(activity, -90, 90, 0) { v ->
            angleValue = v / 2f
            updateLabel()
            onAngle(angleValue)
        }
        parent.addView(angleBar, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        updateLabel()
    }

    private fun updateLabel() {
        angleLabel.text = "کج‌بودن عکس (زاویه‌ی دقیق): " + angleValue + "°"
    }

    /** زاویه‌ی دقیق را صفر می‌کند. */
    fun reset() {
        angleValue = 0f
        angleBar.progress = 90
        updateLabel()
    }
}

/* ------------------------------------------------------------------
 *  بخش «نور و رنگ» (مشترک): حالت‌های آماده، روشنایی، کنتراست، شدت رنگ و تنظیم خودکار.
 * ------------------------------------------------------------------ */
class PhotoAdjustPanel(
    private val activity: PhotoToolActivity,
    parent: LinearLayout,
    private val bitmapForAuto: () -> Bitmap?,
    private val onChange: () -> Unit
) {
    var brightness = 0
        private set
    var contrast = 0
        private set
    var saturation = 100
        private set

    private val brightnessBar: SeekBar
    private val contrastBar: SeekBar
    private val saturationBar: SeekBar
    private val brightnessLabel: TextView
    private val contrastLabel: TextView
    private val saturationLabel: TextView
    private val presetChips: PhotoChips

    init {
        val muted = PhotoUi.color(activity, R.color.text_muted)

        parent.addView(
            PhotoUi.text(activity, "حالت آماده", 14f, muted, true),
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        presetChips = PhotoChips(activity, listOf("اصلی", "سیاه‌وسفید", "اسکن سند (روشن و واضح)"), 0) { index ->
            applyPreset(index)
        }
        val chipsLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        chipsLp.topMargin = PhotoUi.dp(activity, 6)
        parent.addView(presetChips.view, chipsLp)

        brightnessLabel = PhotoUi.text(activity, "", 14f, muted, true)
        val bLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        bLp.topMargin = PhotoUi.dp(activity, 12)
        parent.addView(brightnessLabel, bLp)
        brightnessBar = PhotoUi.seekBar(activity, -100, 100, 0) { v ->
            brightness = v
            updateLabels()
            onChange()
        }
        parent.addView(brightnessBar, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        contrastLabel = PhotoUi.text(activity, "", 14f, muted, true)
        val cLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        cLp.topMargin = PhotoUi.dp(activity, 8)
        parent.addView(contrastLabel, cLp)
        contrastBar = PhotoUi.seekBar(activity, -100, 100, 0) { v ->
            contrast = v
            updateLabels()
            onChange()
        }
        parent.addView(contrastBar, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        saturationLabel = PhotoUi.text(activity, "", 14f, muted, true)
        val sLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        sLp.topMargin = PhotoUi.dp(activity, 8)
        parent.addView(saturationLabel, sLp)
        saturationBar = PhotoUi.seekBar(activity, 0, 200, 100) { v ->
            saturation = v
            updateLabels()
            onChange()
        }
        parent.addView(saturationBar, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val autoButton = PhotoUi.button(activity, "تنظیم خودکار نور", false) { autoAdjust() }
        val resetButton = PhotoUi.button(activity, "بازنشانی نور و رنگ", false) { reset() }
        val autoLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        autoLp.marginEnd = PhotoUi.dp(activity, 8)
        row.addView(autoButton, autoLp)
        row.addView(resetButton, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        val rowLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        rowLp.topMargin = PhotoUi.dp(activity, 14)
        parent.addView(row, rowLp)

        updateLabels()
    }

    private fun updateLabels() {
        brightnessLabel.text = "روشنایی: " + brightness
        contrastLabel.text = "کنتراست: " + contrast
        saturationLabel.text = "شدت رنگ: " + saturation + "٪"
    }

    private fun setValues(b: Int, c: Int, s: Int) {
        brightness = b
        contrast = c
        saturation = s
        brightnessBar.progress = b + 100
        contrastBar.progress = c + 100
        saturationBar.progress = s
        updateLabels()
        onChange()
    }

    private fun applyPreset(index: Int) {
        when (index) {
            0 -> setValues(0, 0, 100)
            1 -> setValues(0, 0, 0)
            else -> setValues(20, 70, 0)
        }
    }

    private fun autoAdjust() {
        val bmp = bitmapForAuto()
        if (bmp == null) {
            android.widget.Toast.makeText(activity, "ابتدا یک عکس انتخاب کنید", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val r = PhotoEditPipeline.autoLevels(bmp)
        setValues(r[0], r[1], saturation)
        presetChips.clear()
    }

    /** همه‌ی مقدارها را به حالت اولیه برمی‌گرداند. */
    fun reset() {
        setValues(0, 0, 100)
        presetChips.select(0, false)
    }

    fun isNeutral(): Boolean = brightness == 0 && contrast == 0 && saturation == 100

    fun colorMatrixOrNull(): ColorMatrix? {
        if (isNeutral()) return null
        return PhotoImageUtils.colorMatrix(brightness, contrast, saturation)
    }

    fun filterOrNull(): ColorFilter? {
        val cm = colorMatrixOrNull() ?: return null
        return ColorMatrixColorFilter(cm)
    }
}
