package com.kafinet.asannet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ===================================================================
 *  شش ابزار بخش «عکس». هر ابزار یک صفحه‌ی جداست و فقط از ابزارهای مشترکِ
 *  PhotoToolsCore.kt و PhotoViews.kt استفاده می‌کند.
 * =================================================================== */

private fun photoTextWatcher(onChange: () -> Unit): TextWatcher {
    return object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            onChange()
        }
    }
}

/* ------------------------------------------------------------------
 *  ۱) تغییر اندازه و کم‌کردن حجم
 * ------------------------------------------------------------------ */

class ResizeOptions(
    val mode: Int,
    val percent: Int,
    val width: Int,
    val height: Int,
    val keepAspect: Boolean,
    val targetBytes: Long,
    val format: PhotoImageUtils.Fmt,
    val quality: Int
)

class PhotoResizeActivity : PhotoBatchActivity<ResizeOptions>() {

    private lateinit var modeChips: PhotoChips
    private lateinit var percentBox: LinearLayout
    private lateinit var percentLabel: TextView
    private lateinit var pixelBox: LinearLayout
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var aspectChips: PhotoChips
    private lateinit var targetChips: PhotoChips
    private lateinit var customTargetInput: EditText
    private lateinit var formatChips: PhotoChips
    private lateinit var qualityBox: LinearLayout
    private lateinit var qualityLabel: TextView
    private var percent = 60
    private var quality = 85

    private val targetKb = intArrayOf(0, 50, 100, 200, 500, 1024)
    private val formats = listOf(PhotoImageUtils.Fmt.JPEG, PhotoImageUtils.Fmt.WEBP, PhotoImageUtils.Fmt.PNG)

    override fun toolTitle(): String = "تغییر اندازه و حجم"

    override fun actionLabel(): String = "کوچک‌کردن عکس‌ها"

    override fun resultHint(): String = "خروجی‌ها بدون اطلاعات مخفی عکس (مثل موقعیت مکانی و مدل گوشی) ساخته می‌شوند."

    private fun updatePercentLabel() {
        percentLabel.text = "اندازه‌ی جدید: " + percent + "٪ اندازه‌ی اصلی"
    }

    private fun updateQualityLabel() {
        qualityLabel.text = "کیفیت: " + quality + "٪"
    }

    private fun updateVisibility() {
        val mode = modeChips.selected
        percentBox.visibility = if (mode == 1) View.VISIBLE else View.GONE
        pixelBox.visibility = if (mode == 2) View.VISIBLE else View.GONE
        customTargetInput.visibility = if (targetChips.selected == 6) View.VISIBLE else View.GONE
        val noTarget = targetChips.selected == 0
        val lossy = formats[formatChips.selected].lossy
        qualityBox.visibility = if (noTarget && lossy) View.VISIBLE else View.GONE
    }

    override fun buildOptions(container: LinearLayout) {
        val card = newCard(container, "تنظیمات")
        val textMain = PhotoUi.color(this, R.color.text_main)

        // ابعاد
        addLabel(card, "ابعاد عکس", 0)
        modeChips = addChips(card, listOf("همان اندازه‌ی اصلی", "درصد", "پیکسل دلخواه"), 0) { updateVisibility() }

        percentBox = LinearLayout(this)
        percentBox.orientation = LinearLayout.VERTICAL
        percentLabel = PhotoUi.text(this, "", 14f, textMain, true)
        percentBox.addView(percentLabel, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        percentBox.addView(
            PhotoUi.seekBar(this, 10, 100, percent) { v ->
                percent = v
                updatePercentLabel()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        val percentLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        percentLp.topMargin = dp(10)
        card.addView(percentBox, percentLp)
        updatePercentLabel()

        pixelBox = LinearLayout(this)
        pixelBox.orientation = LinearLayout.VERTICAL
        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        widthInput = PhotoUi.input(this, "عرض (پیکسل)", true)
        heightInput = PhotoUi.input(this, "ارتفاع (پیکسل)", true)
        val widthLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        widthLp.marginEnd = dp(8)
        sizeRow.addView(widthInput, widthLp)
        sizeRow.addView(heightInput, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        pixelBox.addView(sizeRow, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        addNote(pixelBox, "فقط یکی از دو عدد را هم می‌توانید بنویسید؛ دیگری خودکار حساب می‌شود.", 6)
        addLabel(pixelBox, "نسبت عرض به ارتفاع", 10)
        aspectChips = addChips(pixelBox, listOf("حفظ شود (پیشنهادی)", "اندازه‌ی دقیق (ممکن است کشیده شود)"), 0) { }
        val pixelLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        pixelLp.topMargin = dp(10)
        card.addView(pixelBox, pixelLp)

        // حجم هدف
        addLabel(card, "حجم نهایی هر عکس", 16)
        targetChips = addChips(
            card,
            listOf("بدون محدودیت", "۵۰ کیلوبایت", "۱۰۰ کیلوبایت", "۲۰۰ کیلوبایت", "۵۰۰ کیلوبایت", "۱ مگابایت", "عدد دلخواه"),
            0
        ) { updateVisibility() }
        customTargetInput = PhotoUi.input(this, "حجم دلخواه به کیلوبایت (مثلاً ۷۵)", true)
        val customLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        customLp.topMargin = dp(8)
        card.addView(customTargetInput, customLp)
        addNote(card, "اول کیفیت کم می‌شود و اگر کافی نبود، اندازه‌ی عکس کمی کوچک می‌شود تا به حجم مورد نظر برسد.", 6)

        // فرمت و کیفیت
        addLabel(card, "فرمت خروجی", 16)
        formatChips = addChips(card, listOf("JPG (پیشنهادی)", "WebP", "PNG"), 0) { updateVisibility() }

        qualityBox = LinearLayout(this)
        qualityBox.orientation = LinearLayout.VERTICAL
        qualityLabel = PhotoUi.text(this, "", 14f, textMain, true)
        qualityBox.addView(qualityLabel, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        qualityBox.addView(
            PhotoUi.seekBar(this, 30, 100, quality) { v ->
                quality = v
                updateQualityLabel()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        val qualityLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        qualityLp.topMargin = dp(10)
        card.addView(qualityBox, qualityLp)
        updateQualityLabel()

        updateVisibility()
    }

    override fun readOptions(): ResizeOptions? {
        val mode = modeChips.selected
        var w = 0
        var h = 0
        if (mode == 2) {
            w = PhotoImageUtils.parseInt(widthInput.text.toString()) ?: 0
            h = PhotoImageUtils.parseInt(heightInput.text.toString()) ?: 0
            if (w <= 0 && h <= 0) {
                toast("عرض یا ارتفاع را بنویسید")
                return null
            }
            if (w > 10000 || h > 10000) {
                toast("بیشترین اندازه ۱۰۰۰۰ پیکسل است")
                return null
            }
        }
        var target = 0L
        val ti = targetChips.selected
        if (ti in 1..5) {
            target = targetKb[ti] * 1024L
        } else if (ti == 6) {
            val kb = PhotoImageUtils.parseInt(customTargetInput.text.toString()) ?: 0
            if (kb < 5) {
                toast("حجم دلخواه را به کیلوبایت و حداقل ۵ بنویسید")
                return null
            }
            target = kb * 1024L
        }
        val format = formats[formatChips.selected]
        if (target > 0L && !format.lossy) {
            toast("برای رسیدن به حجم مشخص، فرمت JPG یا WebP را انتخاب کنید")
            return null
        }
        return ResizeOptions(mode, percent, w, h, aspectChips.selected == 0, target, format, quality)
    }

    override fun processOne(source: PhotoSource, options: ResizeOptions, index: Int): PhotoResult? {
        val size = PhotoImageUtils.readSize(this, source.uri) ?: return null
        val origW = size[0]
        val origH = size[1]
        var tw = origW
        var th = origH
        if (options.mode == 1) {
            tw = Math.round(origW * options.percent / 100.0).toInt()
            th = Math.round(origH * options.percent / 100.0).toInt()
        } else if (options.mode == 2) {
            if (options.keepAspect) {
                var s = 1.0
                if (options.width > 0 && options.height > 0) {
                    s = minOf(options.width.toDouble() / origW, options.height.toDouble() / origH)
                } else if (options.width > 0) {
                    s = options.width.toDouble() / origW
                } else if (options.height > 0) {
                    s = options.height.toDouble() / origH
                }
                tw = Math.round(origW * s).toInt()
                th = Math.round(origH * s).toInt()
            } else {
                if (options.width > 0) tw = options.width
                if (options.height > 0) th = options.height
            }
        }
        tw = tw.coerceIn(1, 10000)
        th = th.coerceIn(1, 10000)

        val want = if (options.mode == 0) 6144 else maxOf(tw, th)
        val decoded = PhotoImageUtils.decode(this, source.uri, want) ?: return null
        var bmp = decoded
        if (options.mode != 0 && (tw != decoded.width || th != decoded.height)) {
            bmp = PhotoImageUtils.resizeSmooth(decoded, tw, th)
            if (bmp !== decoded) decoded.recycle()
        }

        val file = PhotoFiles.newFile(this, PhotoImageUtils.baseName(source.name) + "_resized." + options.format.ext)
        var note = ""
        val outW: Int
        val outH: Int
        if (options.targetBytes > 0L && options.format.lossy) {
            val enc = PhotoImageUtils.encodeToTarget(bmp, options.format, options.targetBytes, 30)
            file.writeBytes(enc.bytes)
            outW = enc.width
            outH = enc.height
            if (!enc.reachedTarget) note = "به حجم هدف نرسید؛ این کوچک‌ترین حجمِ ممکن با کیفیت قابل‌قبول بود."
        } else {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                PhotoImageUtils.encodeTo(bmp, options.format, options.quality, out)
            }
            outW = bmp.width
            outH = bmp.height
        }
        bmp.recycle()
        return PhotoResult(file, file.name, options.format.mime, source.name, source.sizeBytes, outW, outH, note)
    }
}

/* ------------------------------------------------------------------
 *  ۲) تبدیل فرمت
 * ------------------------------------------------------------------ */

class ConvertOptions(val format: PhotoImageUtils.Fmt, val quality: Int)

class PhotoConvertActivity : PhotoBatchActivity<ConvertOptions>() {

    private lateinit var formatChips: PhotoChips
    private lateinit var qualityBox: LinearLayout
    private lateinit var qualityLabel: TextView
    private var quality = 90

    private val formats = listOf(
        PhotoImageUtils.Fmt.JPEG,
        PhotoImageUtils.Fmt.PNG,
        PhotoImageUtils.Fmt.WEBP,
        PhotoImageUtils.Fmt.BMP
    )

    override fun toolTitle(): String = "تبدیل فرمت عکس"

    override fun actionLabel(): String = "تبدیل عکس‌ها"

    override fun resultHint(): String = "اندازه‌ی عکس‌ها تغییر نمی‌کند و اطلاعات مخفی (مثل موقعیت مکانی) در خروجی نیست."

    private fun updateQualityLabel() {
        qualityLabel.text = "کیفیت: " + quality + "٪"
    }

    private fun updateVisibility() {
        qualityBox.visibility = if (formats[formatChips.selected].lossy) View.VISIBLE else View.GONE
    }

    override fun buildOptions(container: LinearLayout) {
        val card = newCard(container, "فرمت جدید")
        formatChips = addChips(card, listOf("JPG", "PNG", "WebP", "BMP"), 0) { updateVisibility() }
        addNote(
            card,
            "JPG: مناسب عکس معمولی و حجم کم • PNG: بدون افت کیفیت و با پشتیبانی از شفافیت • " +
                "WebP: حجم کمتر با کیفیت خوب • BMP: بدون فشرده‌سازی (حجم زیاد)",
            10
        )

        qualityBox = LinearLayout(this)
        qualityBox.orientation = LinearLayout.VERTICAL
        qualityLabel = PhotoUi.text(this, "", 14f, PhotoUi.color(this, R.color.text_main), true)
        qualityBox.addView(qualityLabel, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        qualityBox.addView(
            PhotoUi.seekBar(this, 30, 100, quality) { v ->
                quality = v
                updateQualityLabel()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.topMargin = dp(12)
        card.addView(qualityBox, lp)
        updateQualityLabel()
        updateVisibility()
    }

    override fun readOptions(): ConvertOptions? {
        return ConvertOptions(formats[formatChips.selected], quality)
    }

    override fun processOne(source: PhotoSource, options: ConvertOptions, index: Int): PhotoResult? {
        val bmp = PhotoImageUtils.decode(this, source.uri, 6144) ?: return null
        val file = PhotoFiles.newFile(this, PhotoImageUtils.baseName(source.name) + "." + options.format.ext)
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            PhotoImageUtils.encodeTo(bmp, options.format, options.quality, out)
        }
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        return PhotoResult(file, file.name, options.format.mime, source.name, source.sizeBytes, w, h)
    }
}

/* ------------------------------------------------------------------
 *  ۳) عکس به PDF
 * ------------------------------------------------------------------ */

class PdfOptions(val pageMode: Int, val margin: Int, val quality: Int)

class PhotoPdfActivity : PhotoBatchActivity<PdfOptions>() {

    private lateinit var pageChips: PhotoChips
    private lateinit var marginChips: PhotoChips
    private lateinit var qualityChips: PhotoChips

    override val allowReorder: Boolean = true

    override fun toolTitle(): String = "عکس به PDF"

    override fun actionLabel(): String = "ساخت فایل PDF"

    override fun resultHint(): String = "عکس‌ها به ترتیبِ فهرست بالا در صفحه‌های PDF قرار گرفتند. با ▲ و ▼ می‌توانید ترتیب را عوض کنید."

    override fun buildOptions(container: LinearLayout) {
        val card = newCard(container, "تنظیمات PDF")
        addLabel(card, "اندازه‌ی صفحه", 0)
        pageChips = addChips(card, listOf("A4 (عکس وسط صفحه)", "اندازه‌ی خودِ عکس"), 0) { }
        addLabel(card, "حاشیه‌ی صفحه (فقط برای A4)", 14)
        marginChips = addChips(card, listOf("بدون حاشیه", "حاشیه‌ی کم", "حاشیه‌ی متوسط"), 1) { }
        addLabel(card, "کیفیت و حجم فایل", 14)
        qualityChips = addChips(card, listOf("عالی (حجم بیشتر)", "متوسط", "کم (حجم کمتر)"), 1) { }
        addNote(card, "هر عکس یک صفحه می‌شود. عکس‌های افقی به‌صورت خودکار روی صفحه‌ی افقی قرار می‌گیرند.", 10)
    }

    override fun readOptions(): PdfOptions? {
        return PdfOptions(pageChips.selected, marginChips.selected, qualityChips.selected)
    }

    override fun processOne(source: PhotoSource, options: PdfOptions, index: Int): PhotoResult? {
        // این ابزار خروجی را یکجا می‌سازد (تابع produce)
        return null
    }

    override fun produce(
        list: List<PhotoSource>,
        options: PdfOptions,
        progress: (Int, String) -> Unit
    ): BatchOutcome {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = PhotoFiles.newFile(this, "Photos_" + stamp + ".pdf")
        var pages = 0
        var failed = 0
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                val pdf = PhotoPdfWriter(out)
                pdf.begin()
                for (i in list.indices) {
                    progress(i, list[i].name)
                    val ok = try {
                        addPage(pdf, list[i], options)
                    } catch (t: Throwable) {
                        false
                    }
                    if (ok) pages++ else failed++
                }
                if (pages == 0) throw IllegalStateException("no pages")
                pdf.finish()
            }
        } catch (e: Exception) {
            file.delete()
            return BatchOutcome(emptyList(), list.size)
        }
        val note = if (failed > 0) failed.toString() + " عکس به PDF اضافه نشد." else ""
        val result = PhotoResult(file, file.name, "application/pdf", "", 0L, 0, 0, note)
        return BatchOutcome(listOf(result), failed)
    }

    private fun addPage(pdf: PhotoPdfWriter, src: PhotoSource, options: PdfOptions): Boolean {
        val maxSide = when (options.quality) {
            0 -> 2800
            1 -> 2000
            else -> 1500
        }
        val jpegQuality = when (options.quality) {
            0 -> 90
            1 -> 75
            else -> 60
        }
        val decoded = PhotoImageUtils.decode(this, src.uri, maxSide) ?: return false
        var work = decoded
        val longSide = maxOf(decoded.width, decoded.height)
        if (longSide > maxSide) {
            val f = maxSide.toFloat() / longSide
            work = PhotoImageUtils.resizeSmooth(
                decoded,
                Math.round(decoded.width * f),
                Math.round(decoded.height * f)
            )
            if (work !== decoded) decoded.recycle()
        }
        val jpeg = PhotoImageUtils.encodeBytes(work, PhotoImageUtils.Fmt.JPEG, jpegQuality)
        val iw = work.width
        val ih = work.height
        work.recycle()

        if (options.pageMode == 0) {
            val marginPt = when (options.margin) {
                0 -> 0f
                1 -> 18f
                else -> 36f
            }
            val landscape = iw > ih
            val pw = if (landscape) 841.89f else 595.28f
            val ph = if (landscape) 595.28f else 841.89f
            val s = minOf((pw - 2f * marginPt) / iw, (ph - 2f * marginPt) / ih)
            val dw = iw * s
            val dh = ih * s
            pdf.addJpegPage(jpeg, iw, ih, pw, ph, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
        } else {
            val pw = iw * 72f / 150f
            val ph = ih * 72f / 150f
            pdf.addJpegPage(jpeg, iw, ih, pw, ph, 0f, 0f, pw, ph)
        }
        return true
    }
}

/* ------------------------------------------------------------------
 *  ۴) واترمارک مدارک
 * ------------------------------------------------------------------ */

class WatermarkOptions(val text: String, val mode: Int, val sizeLevel: Int, val opacity: Int, val color: Int)

object PhotoWatermark {

    /** متن را روی یک کپی از عکس می‌نویسد (عکس اصلی دست‌نخورده می‌ماند). */
    fun render(context: Context, src: Bitmap, o: WatermarkOptions): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        if (!src.hasAlpha()) out.setHasAlpha(false)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val text = o.text.trim()
        if (text.isEmpty()) return out

        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val minSide = minOf(w, h)
        val factor = when (o.sizeLevel) {
            0 -> 0.035f
            1 -> 0.05f
            else -> 0.075f
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.color = o.color
        paint.alpha = (255 * o.opacity / 100).coerceIn(10, 255)
        paint.textSize = minSide * factor
        paint.typeface = PhotoUi.font(context)

        val maxLineWidth = (w * 0.8f).toInt()
        val naturalWidth = Math.ceil(paint.measureText(text).toDouble()).toInt() + 4
        val layoutWidth = maxOf(1, minOf(naturalWidth, maxLineWidth))
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val lw = layout.width.toFloat()
        val lh = layout.height.toFloat()

        if (o.mode == 0) {
            // تکرارشونده و مورب روی کل عکس
            val cx = w / 2f
            val cy = h / 2f
            val diag = Math.sqrt((w * w + h * h).toDouble()).toFloat()
            val stepX = lw * 1.5f
            val stepY = lh * 3.2f
            canvas.save()
            canvas.rotate(-30f, cx, cy)
            var row = 0
            var y = cy - diag / 2f
            while (y < cy + diag / 2f) {
                val offset = if (row % 2 == 0) 0f else stepX / 2f
                var x = cx - diag / 2f - offset
                while (x < cx + diag / 2f) {
                    canvas.save()
                    canvas.translate(x, y)
                    layout.draw(canvas)
                    canvas.restore()
                    x += stepX
                }
                y += stepY
                row++
            }
            canvas.restore()
        } else if (o.mode == 1) {
            // یک‌بار، وسط عکس و مورب
            canvas.save()
            canvas.rotate(-25f, w / 2f, h / 2f)
            canvas.translate((w - lw) / 2f, (h - lh) / 2f)
            layout.draw(canvas)
            canvas.restore()
        } else {
            // پایین عکس
            canvas.save()
            canvas.translate((w - lw) / 2f, h - lh - minSide * 0.04f)
            layout.draw(canvas)
            canvas.restore()
        }
        return out
    }
}

class PhotoWatermarkActivity : PhotoBatchActivity<WatermarkOptions>() {

    private lateinit var textInput: EditText
    private lateinit var modeChips: PhotoChips
    private lateinit var sizeChips: PhotoChips
    private lateinit var colorChips: PhotoChips
    private lateinit var opacityLabel: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var previewImage: ImageView
    private var opacity = 40
    private var previewBase: Bitmap? = null
    private var previewToken = 0

    private val colors = intArrayOf(
        Color.parseColor("#6B7280"),
        Color.parseColor("#111111"),
        Color.parseColor("#DC2626"),
        Color.parseColor("#FFFFFF")
    )

    override fun toolTitle(): String = "واترمارک مدارک"

    override fun actionLabel(): String = "ساخت عکس‌های واترمارک‌دار"

    override fun resultHint(): String = "عکس اصلی دست‌نخورده می‌ماند؛ نسخه‌ی واترمارک‌دار جدا ساخته شد."

    private fun updateOpacityLabel() {
        opacityLabel.text = "پررنگی نوشته: " + opacity + "٪"
    }

    private fun currentOptions(): WatermarkOptions {
        return WatermarkOptions(
            textInput.text.toString(),
            modeChips.selected,
            sizeChips.selected,
            opacity,
            colors[colorChips.selected]
        )
    }

    override fun buildOptions(container: LinearLayout) {
        val card = newCard(container, "نوشته‌ی واترمارک")

        textInput = PhotoUi.input(this, "مثلاً: فقط برای ثبت‌نام در ...", false)
        card.addView(textInput, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        addLabel(card, "نوشته‌ی آماده", 10)
        val quickTexts = arrayOf("فقط برای ", "فقط جهت ثبت‌نام", "کپی مدرک", "")
        var quickRef: PhotoChips? = null
        val quickChips = PhotoChips(
            this,
            listOf("فقط برای ...", "فقط جهت ثبت‌نام", "کپی مدرک", "افزودن تاریخ امروز"),
            0
        ) { index ->
            if (index == 3) {
                val d = PersianDateUtils.todayJalali()
                val date = PersianDateUtils.formatDate(d[0], d[1], d[2])
                val current = textInput.text.toString().trim()
                val joined = if (current.isEmpty()) date else current + " - " + date
                textInput.setText(joined)
            } else {
                textInput.setText(quickTexts[index])
            }
            textInput.setSelection(textInput.text.length)
            quickRef?.clear()
        }
        quickRef = quickChips
        quickChips.clear()
        card.addView(quickChips.view, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        addLabel(card, "چیدمان", 14)
        modeChips = addChips(card, listOf("تکرارشده روی کل عکس", "یک‌بار وسط عکس", "پایین عکس"), 0) { renderPreview() }

        addLabel(card, "اندازه‌ی نوشته", 14)
        sizeChips = addChips(card, listOf("کوچک", "متوسط", "بزرگ"), 1) { renderPreview() }

        addLabel(card, "رنگ نوشته", 14)
        colorChips = addChips(card, listOf("طوسی", "مشکی", "قرمز", "سفید"), 0) { renderPreview() }

        opacityLabel = PhotoUi.text(this, "", 14f, PhotoUi.color(this, R.color.text_muted), true)
        val opLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        opLp.topMargin = dp(14)
        card.addView(opacityLabel, opLp)
        card.addView(
            PhotoUi.seekBar(this, 10, 90, opacity) { v ->
                opacity = v
                updateOpacityLabel()
                renderPreview()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        updateOpacityLabel()

        textInput.addTextChangedListener(photoTextWatcher { renderPreview() })

        // پیش‌نمایش
        previewCard = newCard(container, "پیش‌نمایش (اولین عکس)")
        previewImage = ImageView(this)
        previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        previewImage.adjustViewBounds = true
        previewCard.addView(previewImage, LinearLayout.LayoutParams(PhotoUi.MATCH, dp(320)))
        previewCard.visibility = View.GONE
    }

    override fun onSourcesChanged() {
        val first = if (sources.isEmpty()) null else sources[0]
        previewToken++
        val token = previewToken
        if (first == null) {
            previewBase = null
            previewCard.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoWatermarkActivity, first.uri, 900) }
            if (token == previewToken) {
                previewBase = bmp
                renderPreview()
            }
        }
    }

    private fun renderPreview() {
        val base = previewBase
        if (base == null) {
            previewCard.visibility = View.GONE
            return
        }
        previewCard.visibility = View.VISIBLE
        val rendered = PhotoWatermark.render(this, base, currentOptions())
        previewImage.setImageBitmap(rendered)
    }

    override fun readOptions(): WatermarkOptions? {
        val o = currentOptions()
        if (o.text.trim().isEmpty()) {
            toast("نوشته‌ی واترمارک را بنویسید")
            return null
        }
        return o
    }

    override fun processOne(source: PhotoSource, options: WatermarkOptions, index: Int): PhotoResult? {
        val bmp = PhotoImageUtils.decode(this, source.uri, 6144) ?: return null
        val marked = PhotoWatermark.render(this, bmp, options)
        bmp.recycle()
        val isPng = source.name.lowercase(Locale.US).endsWith(".png")
        val format = if (isPng) PhotoImageUtils.Fmt.PNG else PhotoImageUtils.Fmt.JPEG
        val file = PhotoFiles.newFile(this, PhotoImageUtils.baseName(source.name) + "_wm." + format.ext)
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            PhotoImageUtils.encodeTo(marked, format, 92, out)
        }
        val w = marked.width
        val h = marked.height
        marked.recycle()
        return PhotoResult(file, file.name, format.mime, source.name, source.sizeBytes, w, h)
    }
}

/* ------------------------------------------------------------------
 *  ۵) عکس پرسنلی (۳×۴، ۴×۶ و ...) با برگه‌ی چاپ
 * ------------------------------------------------------------------ */

class PhotoIdActivity : PhotoToolActivity() {

    private lateinit var frameView: PhotoFrameView
    private lateinit var sizeChips: PhotoChips
    private lateinit var customBox: LinearLayout
    private lateinit var customW: EditText
    private lateinit var customH: EditText
    private lateinit var fillChips: PhotoChips
    private lateinit var sheetChips: PhotoChips
    private lateinit var copiesBox: LinearLayout
    private lateinit var copiesChips: PhotoChips
    private lateinit var capacityText: TextView
    private lateinit var panel: PhotoResultPanel
    private var building = false

    private val presetsCm = arrayOf(
        doubleArrayOf(3.0, 4.0),
        doubleArrayOf(4.0, 6.0),
        doubleArrayOf(2.0, 3.0),
        doubleArrayOf(3.5, 4.5)
    )
    private val fillColors = intArrayOf(Color.WHITE, Color.parseColor("#E5E7EB"), Color.parseColor("#BFDBFE"))
    private val copiesValues = intArrayOf(0, 4, 6, 8, 12)

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun toolTitle(): String = "عکس پرسنلی"

    private fun cmToPx(cm: Double): Int = Math.round(cm / 2.54 * 300.0).toInt()

    private fun formatCm(v: Double): String {
        return if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
    }

    private fun sheetWidthPx(mode: Int): Int = if (mode == 1) 1181 else 2480

    private fun sheetHeightPx(mode: Int): Int = if (mode == 1) 1772 else 3508

    private fun currentSizeCm(showError: Boolean): DoubleArray? {
        val i = sizeChips.selected
        if (i in 0..3) return presetsCm[i]
        val w = PhotoImageUtils.parseNumber(customW.text.toString())
        val h = PhotoImageUtils.parseNumber(customH.text.toString())
        if (w == null || h == null || w < 1.0 || h < 1.0 || w > 20.0 || h > 20.0) {
            if (showError) toast("عرض و ارتفاع را به سانتی‌متر بنویسید (بین ۱ تا ۲۰)")
            return null
        }
        return doubleArrayOf(w, h)
    }

    private fun applySizeChoice() {
        customBox.visibility = if (sizeChips.selected == 4) View.VISIBLE else View.GONE
        val size = currentSizeCm(false)
        if (size != null) {
            frameView.setFrameRatio(size[0].toFloat(), size[1].toFloat())
        }
        updateCapacity()
    }

    private fun applySheetChoice() {
        val mode = sheetChips.selected
        copiesBox.visibility = if (mode == 0) View.GONE else View.VISIBLE
        updateCapacity()
    }

    private fun updateCapacity() {
        val mode = sheetChips.selected
        val size = currentSizeCm(false)
        if (mode == 0 || size == null) {
            capacityText.visibility = View.GONE
            return
        }
        val cap = PhotoImageUtils.sheetCapacity(sheetWidthPx(mode), sheetHeightPx(mode), cmToPx(size[0]), cmToPx(size[1]), 47, 24)
        capacityText.text = "روی این برگه تا " + cap + " عکس جا می‌شود (فاصله‌ی ۲ میلی‌متر برای برش)."
        capacityText.visibility = View.VISIBLE
    }

    override fun buildContent(container: LinearLayout) {
        val card1 = newCard(container, "۱) عکس را انتخاب و داخل کادر تنظیم کنید")
        card1.addView(
            PhotoUi.button(this, "انتخاب عکس از گالری", false) { pickPhoto.launch("image/*") },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        frameView = PhotoFrameView(this)
        val frameLp = LinearLayout.LayoutParams(PhotoUi.MATCH, dp(380))
        frameLp.topMargin = dp(10)
        card1.addView(frameView, frameLp)
        addNote(
            card1,
            "با یک انگشت عکس را جابه‌جا و با دو انگشت بزرگ و کوچک کنید تا صورت وسط بیضی قرار بگیرد. هر چه داخل کادر باشد در خروجی می‌آید.",
            8
        )

        val card2 = newCard(container, "۲) اندازه و نوع خروجی")
        addLabel(card2, "اندازه‌ی چاپی عکس", 0)
        sizeChips = addChips(
            card2,
            listOf("۳×۴ سانتی‌متر", "۴×۶ سانتی‌متر", "۲×۳", "۳٫۵×۴٫۵", "اندازه‌ی دلخواه"),
            0
        ) { applySizeChoice() }

        customBox = LinearLayout(this)
        customBox.orientation = LinearLayout.HORIZONTAL
        customW = PhotoUi.input(this, "عرض (سانتی‌متر)", true)
        customH = PhotoUi.input(this, "ارتفاع (سانتی‌متر)", true)
        val cwLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        cwLp.marginEnd = dp(8)
        customBox.addView(customW, cwLp)
        customBox.addView(customH, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        val cbLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        cbLp.topMargin = dp(8)
        card2.addView(customBox, cbLp)
        customW.addTextChangedListener(photoTextWatcher { applySizeChoice() })
        customH.addTextChangedListener(photoTextWatcher { applySizeChoice() })

        addLabel(card2, "رنگ جاهای خالی کادر", 14)
        fillChips = addChips(card2, listOf("سفید", "خاکستری روشن", "آبی روشن"), 0) { index ->
            frameView.fillColor = fillColors[index]
        }

        addLabel(card2, "نوع خروجی", 14)
        sheetChips = addChips(card2, listOf("فقط یک عکس", "برگه‌ی چاپ ۱۰×۱۵", "برگه‌ی چاپ A4"), 0) { applySheetChoice() }

        copiesBox = LinearLayout(this)
        copiesBox.orientation = LinearLayout.VERTICAL
        addLabel(copiesBox, "تعداد نسخه روی برگه", 14)
        copiesChips = addChips(copiesBox, listOf("هر چقدر جا شد", "۴ عدد", "۶ عدد", "۸ عدد", "۱۲ عدد"), 0) { }
        capacityText = PhotoUi.text(this, "", 12.5f, PhotoUi.color(this, R.color.text_muted))
        val capLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        capLp.topMargin = dp(8)
        copiesBox.addView(capacityText, capLp)
        card2.addView(copiesBox, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        addNote(card2, "خروجی با کیفیت ۳۰۰ dpi (مناسب چاپخانه) ساخته می‌شود.", 10)

        val build = PhotoUi.button(this, "ساخت عکس", true) { buildPhoto() }
        val buildLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        buildLp.bottomMargin = dp(12)
        container.addView(build, buildLp)

        panel = PhotoResultPanel(this, container)

        applySizeChoice()
        applySheetChoice()
    }

    private fun loadPhoto(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoIdActivity, uri, 2400) }
            if (bmp == null) {
                toast("این عکس باز نشد")
                return@launch
            }
            frameView.setBitmap(bmp)
            panel.hide()
        }
    }

    private fun buildPhoto() {
        if (building) return
        if (!frameView.hasBitmap()) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        val size = currentSizeCm(true) ?: return
        val pxW = cmToPx(size[0])
        val pxH = cmToPx(size[1])
        val photo = frameView.renderCrop(pxW, pxH)
        if (photo == null) {
            toast("ساخت عکس ممکن نشد")
            return
        }
        val sheetMode = sheetChips.selected
        val copies = copiesValues[copiesChips.selected]
        building = true
        lifecycleScope.launch {
            val made = withContext(Dispatchers.Default) {
                try {
                    makeOutput(photo, sheetMode, copies, size)
                } catch (t: Throwable) {
                    null
                }
            }
            building = false
            if (made == null) {
                toast("ساخت خروجی ممکن نشد؛ اندازه‌ی عکس برای این برگه مناسب نیست")
                return@launch
            }
            panel.show(made.first, made.second)
        }
    }

    private fun makeOutput(photo: Bitmap, sheetMode: Int, copies: Int, size: DoubleArray): Pair<PhotoResult, String>? {
        val sizeLabel = formatCm(size[0]) + "x" + formatCm(size[1])
        val sizeText = formatCm(size[0]) + " در " + formatCm(size[1]) + " سانتی‌متر"
        val pxW = photo.width
        val pxH = photo.height
        val name: String
        val info: String
        val bytes: ByteArray
        if (sheetMode == 0) {
            bytes = PhotoImageUtils.setJpegDpi(PhotoImageUtils.encodeBytes(photo, PhotoImageUtils.Fmt.JPEG, 95), 300)
            name = "ID_" + sizeLabel + ".jpg"
            info = "اندازه‌ی چاپ: " + sizeText + " • " + pxW + " در " + pxH + " پیکسل (۳۰۰ dpi) • حجم: " +
                PhotoImageUtils.formatSize(bytes.size.toLong())
        } else {
            val sheetW = sheetWidthPx(sheetMode)
            val sheetH = sheetHeightPx(sheetMode)
            val capacity = PhotoImageUtils.sheetCapacity(sheetW, sheetH, pxW, pxH, 47, 24)
            val sheet = PhotoImageUtils.composeSheet(photo, sheetW, sheetH, 47, 24, copies) ?: return null
            bytes = PhotoImageUtils.setJpegDpi(PhotoImageUtils.encodeBytes(sheet, PhotoImageUtils.Fmt.JPEG, 95), 300)
            sheet.recycle()
            val count = if (copies > 0) minOf(copies, capacity) else capacity
            val sheetName = if (sheetMode == 1) "10x15" else "A4"
            val sheetText = if (sheetMode == 1) "۱۰×۱۵ سانتی‌متر" else "A4"
            name = "ID_" + sizeLabel + "_sheet_" + sheetName + ".jpg"
            info = "برگه‌ی " + sheetText + " با " + count + " عکس " + sizeText + " • ۳۰۰ dpi • حجم: " +
                PhotoImageUtils.formatSize(bytes.size.toLong())
        }
        photo.recycle()
        val file = PhotoFiles.newFile(this, name)
        file.writeBytes(bytes)
        val result = PhotoResult(file, file.name, "image/jpeg", "", 0L, pxW, pxH)
        return Pair(result, info)
    }
}

/* ------------------------------------------------------------------
 *  ۶) ویرایش: برش، چرخش، آینه، نور و حالت اسکن
 * ------------------------------------------------------------------ */

class PhotoEditorActivity : PhotoToolActivity() {

    private lateinit var cropView: PhotoCropView
    private lateinit var ratioChips: PhotoChips
    private lateinit var filterChips: PhotoChips
    private lateinit var formatChips: PhotoChips
    private lateinit var brightnessBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var saturationBar: SeekBar
    private lateinit var brightnessLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var saturationLabel: TextView
    private lateinit var qualityBox: LinearLayout
    private lateinit var qualityLabel: TextView
    private lateinit var panel: PhotoResultPanel

    private var sourceUri: Uri? = null
    private var sourceName = "photo"
    private var sourceBytes = -1L
    private var baseBitmap: Bitmap? = null
    private var displayBitmap: Bitmap? = null
    private var turns = 0
    private var flipH = false
    private var flipV = false
    private var brightness = 0
    private var contrast = 0
    private var saturation = 100
    private var quality = 90
    private var working = false

    private val ratios = floatArrayOf(0f, 1f, 3f / 4f, 4f / 3f, 9f / 16f, 16f / 9f, 2f / 3f, 3f / 2f)
    private val formats = listOf(PhotoImageUtils.Fmt.JPEG, PhotoImageUtils.Fmt.PNG, PhotoImageUtils.Fmt.WEBP)

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun toolTitle(): String = "ویرایش عکس"

    private fun addSeek(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit
    ): Pair<SeekBar, TextView> {
        val title = PhotoUi.text(this, label, 14f, PhotoUi.color(this, R.color.text_muted), true)
        val tlp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        tlp.topMargin = dp(10)
        parent.addView(title, tlp)
        val bar = PhotoUi.seekBar(this, min, max, value, onChange)
        parent.addView(bar, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        return Pair(bar, title)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView {
        val b = PhotoUi.button(this, label, false, onClick)
        b.setPadding(dp(6), dp(10), dp(6), dp(10))
        return b
    }

    override fun buildContent(container: LinearLayout) {
        val card1 = newCard(container, "۱) عکس و برش")
        card1.addView(
            PhotoUi.button(this, "انتخاب عکس از گالری", false) { pickPhoto.launch("image/*") },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        cropView = PhotoCropView(this)
        val cropLp = LinearLayout.LayoutParams(PhotoUi.MATCH, dp(360))
        cropLp.topMargin = dp(10)
        card1.addView(cropView, cropLp)
        addNote(card1, "کادر را بکشید تا جابه‌جا شود؛ از گوشه‌ها بزرگ و کوچکش کنید.", 8)

        addLabel(card1, "نسبت کادر", 10)
        ratioChips = addChips(
            card1,
            listOf("آزاد", "۱:۱", "۳:۴", "۴:۳", "۹:۱۶", "۱۶:۹", "۲:۳", "۳:۲"),
            0
        ) { index -> cropView.setRatio(ratios[index]) }

        addLabel(card1, "چرخش و آینه", 14)
        val labels = arrayOf("چرخش راست", "چرخش چپ", "آینه افقی", "آینه عمودی")
        for (rowIndex in 0 until 2) {
            val rotRow = LinearLayout(this)
            rotRow.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 2) {
                val i = rowIndex * 2 + col
                val b = actionButton(labels[i]) { transformAction(i) }
                val lp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
                if (col == 0) lp.marginEnd = dp(8)
                rotRow.addView(b, lp)
            }
            val rowLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            if (rowIndex == 1) rowLp.topMargin = dp(8)
            card1.addView(rotRow, rowLp)
        }

        val card2 = newCard(container, "۲) نور و رنگ")
        addLabel(card2, "حالت آماده", 0)
        filterChips = addChips(card2, listOf("اصلی", "سیاه‌وسفید", "اسکن سند (روشن و واضح)"), 0) { index -> applyPreset(index) }

        val b = addSeek(card2, "", -100, 100, 0) { v ->
            brightness = v
            updateAdjustLabels()
            applyFilter()
        }
        brightnessBar = b.first
        brightnessLabel = b.second
        val c = addSeek(card2, "", -100, 100, 0) { v ->
            contrast = v
            updateAdjustLabels()
            applyFilter()
        }
        contrastBar = c.first
        contrastLabel = c.second
        val s = addSeek(card2, "", 0, 200, 100) { v ->
            saturation = v
            updateAdjustLabels()
            applyFilter()
        }
        saturationBar = s.first
        saturationLabel = s.second
        updateAdjustLabels()

        val card3 = newCard(container, "۳) خروجی")
        addLabel(card3, "فرمت", 0)
        formatChips = addChips(card3, listOf("JPG", "PNG", "WebP"), 0) { updateQualityVisibility() }
        qualityBox = LinearLayout(this)
        qualityBox.orientation = LinearLayout.VERTICAL
        qualityLabel = PhotoUi.text(this, "", 14f, PhotoUi.color(this, R.color.text_muted), true)
        val qlp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        qlp.topMargin = dp(10)
        qualityBox.addView(qualityLabel, qlp)
        qualityBox.addView(
            PhotoUi.seekBar(this, 40, 100, quality) { v ->
                quality = v
                updateQualityLabel()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        card3.addView(qualityBox, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        updateQualityLabel()

        val build = PhotoUi.button(this, "ساخت عکس نهایی", true) { exportResult() }
        val buildLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        buildLp.bottomMargin = dp(12)
        container.addView(build, buildLp)

        panel = PhotoResultPanel(this, container)
    }

    private fun updateQualityLabel() {
        qualityLabel.text = "کیفیت: " + quality + "٪"
    }

    private fun updateQualityVisibility() {
        qualityBox.visibility = if (formats[formatChips.selected].lossy) View.VISIBLE else View.GONE
    }

    private fun updateAdjustLabels() {
        brightnessLabel.text = "روشنایی: " + brightness
        contrastLabel.text = "کنتراست: " + contrast
        saturationLabel.text = "شدت رنگ: " + saturation + "٪"
    }

    private fun applyPreset(index: Int) {
        when (index) {
            0 -> {
                brightness = 0
                contrast = 0
                saturation = 100
            }
            1 -> {
                brightness = 0
                contrast = 0
                saturation = 0
            }
            else -> {
                brightness = 20
                contrast = 70
                saturation = 0
            }
        }
        brightnessBar.progress = brightness + 100
        contrastBar.progress = contrast + 100
        saturationBar.progress = saturation
        updateAdjustLabels()
        applyFilter()
    }

    private fun applyFilter() {
        if (!::cropView.isInitialized) return
        if (brightness == 0 && contrast == 0 && saturation == 100) {
            cropView.setPreviewFilter(null)
        } else {
            cropView.setPreviewFilter(ColorMatrixColorFilter(PhotoImageUtils.colorMatrix(brightness, contrast, saturation)))
        }
    }

    private fun loadPhoto(uri: Uri) {
        val info = PhotoImageUtils.queryInfo(this, uri)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoEditorActivity, uri, 1600) }
            if (bmp == null) {
                toast("این عکس باز نشد")
                return@launch
            }
            sourceUri = uri
            sourceName = info.first
            sourceBytes = info.second
            baseBitmap = bmp
            turns = 0
            flipH = false
            flipV = false
            refreshDisplay()
            panel.hide()
        }
    }

    private fun refreshDisplay() {
        val base = baseBitmap ?: return
        val shown = PhotoImageUtils.transform(base, turns, flipH, flipV)
        val old = displayBitmap
        displayBitmap = shown
        cropView.setBitmap(shown)
        if (old != null && old !== base && old !== shown) old.recycle()
        applyFilter()
    }

    private fun transformAction(which: Int) {
        if (baseBitmap == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        when (which) {
            0 -> turns = (turns + 1) % 4
            1 -> turns = (turns + 3) % 4
            2 -> flipH = !flipH
            else -> flipV = !flipV
        }
        refreshDisplay()
    }

    private fun exportResult() {
        if (working) return
        val uri = sourceUri
        if (uri == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        val crop = cropView.normalizedCrop()
        val format = formats[formatChips.selected]
        val t = turns
        val fh = flipH
        val fv = flipV
        val b = brightness
        val c = contrast
        val s = saturation
        val q = quality
        val name = sourceName
        val originalBytes = sourceBytes
        working = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    renderFinal(uri, crop, t, fh, fv, b, c, s, format, q, name, originalBytes)
                } catch (e: Throwable) {
                    null
                }
            }
            working = false
            if (result == null) {
                toast("ساخت عکس ممکن نشد")
                return@launch
            }
            val info = "ابعاد: " + result.width + " در " + result.height + " پیکسل • حجم: " +
                PhotoImageUtils.formatSize(result.file.length())
            panel.show(result, info)
        }
    }

    private fun renderFinal(
        uri: Uri,
        crop: android.graphics.RectF,
        quarterTurns: Int,
        fh: Boolean,
        fv: Boolean,
        b: Int,
        c: Int,
        s: Int,
        format: PhotoImageUtils.Fmt,
        q: Int,
        name: String,
        originalBytes: Long
    ): PhotoResult? {
        val full = PhotoImageUtils.decode(this, uri, 4096) ?: return null
        val rotated = PhotoImageUtils.transform(full, quarterTurns, fh, fv)
        if (rotated !== full) full.recycle()

        var x = Math.round(crop.left * rotated.width)
        var y = Math.round(crop.top * rotated.height)
        var w = Math.round((crop.right - crop.left) * rotated.width)
        var h = Math.round((crop.bottom - crop.top) * rotated.height)
        x = x.coerceIn(0, rotated.width - 1)
        y = y.coerceIn(0, rotated.height - 1)
        w = w.coerceIn(1, rotated.width - x)
        h = h.coerceIn(1, rotated.height - y)
        val cropped = if (x == 0 && y == 0 && w == rotated.width && h == rotated.height) {
            rotated
        } else {
            val piece = Bitmap.createBitmap(rotated, x, y, w, h)
            rotated.recycle()
            piece
        }

        var finalBitmap = cropped
        if (b != 0 || c != 0 || s != 100) {
            val colored = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
            if (!cropped.hasAlpha()) colored.setHasAlpha(false)
            val canvas = Canvas(colored)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(PhotoImageUtils.colorMatrix(b, c, s))
            canvas.drawBitmap(cropped, 0f, 0f, paint)
            cropped.recycle()
            finalBitmap = colored
        }

        val file = PhotoFiles.newFile(this, PhotoImageUtils.baseName(name) + "_edit." + format.ext)
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            PhotoImageUtils.encodeTo(finalBitmap, format, q, out)
        }
        val outW = finalBitmap.width
        val outH = finalBitmap.height
        finalBitmap.recycle()
        return PhotoResult(file, file.name, format.mime, name, originalBytes, outW, outH)
    }
}
