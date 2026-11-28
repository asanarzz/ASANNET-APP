package com.kafinet.asannet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

internal fun photoTextWatcher(onChange: () -> Unit): TextWatcher {
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
        refreshPlan()
    }

    private fun updateQualityLabel() {
        qualityLabel.text = "کیفیت: " + quality + "٪"
        refreshPlan()
    }

    private fun updateVisibility() {
        val mode = modeChips.selected
        percentBox.visibility = if (mode == 1) View.VISIBLE else View.GONE
        pixelBox.visibility = if (mode == 2) View.VISIBLE else View.GONE
        customTargetInput.visibility = if (targetChips.selected == 6) View.VISIBLE else View.GONE
        val noTarget = targetChips.selected == 0
        val lossy = formats[formatChips.selected].lossy
        qualityBox.visibility = if (noTarget && lossy) View.VISIBLE else View.GONE
        refreshPlan()
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
        widthInput.addTextChangedListener(photoTextWatcher { refreshPlan() })
        heightInput.addTextChangedListener(photoTextWatcher { refreshPlan() })
        val widthLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        widthLp.marginEnd = dp(8)
        sizeRow.addView(widthInput, widthLp)
        sizeRow.addView(heightInput, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        pixelBox.addView(sizeRow, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        addNote(pixelBox, "فقط یکی از دو عدد را هم می‌توانید بنویسید؛ دیگری خودکار حساب می‌شود.", 6)
        addLabel(pixelBox, "نسبت عرض به ارتفاع", 10)
        aspectChips = addChips(pixelBox, listOf("حفظ شود (پیشنهادی)", "اندازه‌ی دقیق (ممکن است کشیده شود)"), 0) { refreshPlan() }
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
        customTargetInput.addTextChangedListener(photoTextWatcher { refreshPlan() })
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

    override fun describePlan(): String {
        val s = firstPhotoSize
        var tw = 0
        var th = 0
        if (s != null && s.size >= 2 && s[0] > 0 && s[1] > 0) {
            val w = s[0]
            val h = s[1]
            tw = w
            th = h
            val mode = modeChips.selected
            if (mode == 1) {
                tw = Math.max(1, Math.round(w * percent / 100.0).toInt())
                th = Math.max(1, Math.round(h * percent / 100.0).toInt())
            } else if (mode == 2) {
                val bw = PhotoImageUtils.parseInt(widthInput.text.toString()) ?: 0
                val bh = PhotoImageUtils.parseInt(heightInput.text.toString()) ?: 0
                if (aspectChips.selected == 0) {
                    var sc = 1.0
                    if (bw > 0 && bh > 0) {
                        sc = minOf(bw.toDouble() / w, bh.toDouble() / h)
                    } else if (bw > 0) {
                        sc = bw.toDouble() / w
                    } else if (bh > 0) {
                        sc = bh.toDouble() / h
                    }
                    tw = Math.max(1, Math.round(w * sc).toInt())
                    th = Math.max(1, Math.round(h * sc).toInt())
                } else {
                    if (bw > 0) tw = bw
                    if (bh > 0) th = bh
                }
            }
        }
        val parts = ArrayList<String>()
        if (tw > 0 && th > 0) parts.add("ابعاد خروجی: " + tw + " در " + th + " پیکسل")
        val ti = targetChips.selected
        if (ti in 1..5) {
            parts.add("حجم: حداکثر " + PhotoImageUtils.formatSize(targetKb[ti] * 1024L))
        } else if (ti == 6) {
            val kb = PhotoImageUtils.parseInt(customTargetInput.text.toString()) ?: 0
            if (kb >= 5) parts.add("حجم: حداکثر " + kb + " کیلوبایت")
        } else if (formats[formatChips.selected].lossy) {
            parts.add("کیفیت " + quality + "٪")
        }
        parts.add("فرمت " + formats[formatChips.selected].label)
        return parts.joinToString(" • ")
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
        refreshPlan()
    }

    private fun updateVisibility() {
        qualityBox.visibility = if (formats[formatChips.selected].lossy) View.VISIBLE else View.GONE
        refreshPlan()
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

    override fun describePlan(): String {
        val f = formats[formatChips.selected]
        var line = "فرمت خروجی: " + f.label
        if (f.lossy) line += " • کیفیت " + quality + "٪"
        line += " • اندازه‌ی عکس تغییر نمی‌کند"
        return line
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
        pageChips = addChips(card, listOf("A4 (عکس وسط صفحه)", "اندازه‌ی خودِ عکس"), 0) { refreshPlan() }
        addLabel(card, "حاشیه‌ی صفحه (فقط برای A4)", 14)
        marginChips = addChips(card, listOf("بدون حاشیه", "حاشیه‌ی کم", "حاشیه‌ی متوسط"), 1) { refreshPlan() }
        addLabel(card, "کیفیت و حجم فایل", 14)
        qualityChips = addChips(card, listOf("عالی (حجم بیشتر)", "متوسط", "کم (حجم کمتر)"), 1) { refreshPlan() }
        addNote(card, "هر عکس یک صفحه می‌شود. عکس‌های افقی به‌صورت خودکار روی صفحه‌ی افقی قرار می‌گیرند.", 10)
    }

    override fun onSourcesChanged() {
        refreshPlan()
    }

    override fun describePlan(): String {
        val pageText = if (pageChips.selected == 0) "صفحه‌ی A4" else "اندازه‌ی خودِ عکس"
        val marginText = if (pageChips.selected == 0) " • " + listOf("بدون حاشیه", "حاشیه‌ی کم", "حاشیه‌ی متوسط")[marginChips.selected] else ""
        val qualityText = listOf("کیفیت عالی", "کیفیت متوسط", "حجم کم")[qualityChips.selected]
        return "PDF با " + sources.size + " صفحه • " + pageText + marginText + " • " + qualityText
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

    override val usesDefaultPreview: Boolean = false

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
        // پیش‌نمایش ثابت بالای صفحه (با اسکرول‌کردن تنظیمات جابه‌جا نمی‌شود)
        previewImage = ImageView(this)
        previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        previewImage.background = PhotoUi.rounded(PhotoUi.color(this, R.color.surface), dp(14).toFloat())
        pinned.addView(previewImage, LinearLayout.LayoutParams(PhotoUi.MATCH, pinnedHeight(0.3f, 140, 320)))
        pinned.visibility = View.GONE
    }

    override fun onSourcesChanged() {
        val first = if (sources.isEmpty()) null else sources[0]
        previewToken++
        val token = previewToken
        if (first == null) {
            previewBase = null
            pinned.visibility = View.GONE
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
            pinned.visibility = View.GONE
            return
        }
        pinned.visibility = View.VISIBLE
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
