package com.kafinet.asannet

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale

/* ===================================================================
 *  ابزارهای عکس (بخش «عکس»)
 *
 *  همه‌چیز این بخش در چند فایل جدا و مستقل نوشته شده (Photo*.kt) و به هیچ بخش دیگری
 *  از برنامه وابسته نیست، جز چند ابزار آماده‌ی خودِ برنامه:
 *   - AppDownloadStorage / DownloadHelper : ذخیره در پوشه‌ی Downloads/آسان نت
 *   - FileProvider (همان که برای اشتراک‌گذاری برنامه استفاده می‌شود)
 *  تمام کارها روی خودِ گوشی انجام می‌شود (بدون سرور، بدون اینترنت، بدون هزینه).
 * =================================================================== */

/** یک عکسِ انتخاب‌شده توسط کاربر. */
class PhotoSource(val uri: Uri, val name: String, val sizeBytes: Long)

/** خروجی ساخته‌شده: فایلی در حافظه‌ی موقت برنامه تا کاربر ذخیره یا ارسالش کند. */
class PhotoResult(
    val file: File,
    val displayName: String,
    val mime: String,
    val sourceName: String,
    val sourceBytes: Long,
    val width: Int,
    val height: Int,
    val note: String = ""
)

class BatchOutcome(val results: List<PhotoResult>, val failed: Int)

/* ------------------------------------------------------------------
 *  ظاهر: ساخت ویوها با کد (هم‌رنگ و هم‌فونت با بقیه‌ی برنامه، سازگار با حالت تاریک)
 * ------------------------------------------------------------------ */
object PhotoUi {

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** ضریب بزرگ‌نمایی همه‌ی نوشته‌های بخش عکس (برای خوانایی بهتر). */
    const val TEXT_SCALE = 1.3f

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun color(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

    fun font(context: Context): Typeface? {
        return try {
            ResourcesCompat.getFont(context, R.font.yekan_boom)
        } catch (e: Exception) {
            null
        }
    }

    fun rounded(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusPx
        d.setColor(fill)
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    /** پس‌زمینه‌ی گرد با موجِ لمس. */
    fun ripple(content: Drawable, radiusPx: Float, rippleColor: Int): Drawable {
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.RECTANGLE
        mask.cornerRadius = radiusPx
        mask.setColor(Color.BLACK)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, colorInt: Int, bold: Boolean = false): TextView {
        val tv = TextView(context)
        tv.text = value
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * TEXT_SCALE)
        tv.setTextColor(colorInt)
        tv.setTypeface(font(context), if (bold) Typeface.BOLD else Typeface.NORMAL)
        tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        return tv
    }

    fun card(context: Context): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        val p = dp(context, 14)
        box.setPadding(p, p, p, p)
        box.background = rounded(color(context, R.color.surface), dp(context, 18).toFloat())
        box.elevation = dp(context, 2).toFloat()
        return box
    }

    fun button(context: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val primary = color(context, R.color.primary)
        val tv = text(context, label, 15f, if (filled) Color.WHITE else primary, true)
        tv.gravity = Gravity.CENTER
        val radius = dp(context, 14).toFloat()
        val bg = if (filled) {
            rounded(primary, radius)
        } else {
            rounded(color(context, R.color.surface), radius, primary, dp(context, 1))
        }
        tv.background = ripple(bg, radius, if (filled) 0x33FFFFFF else 0x224F46E5)
        tv.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    fun input(context: Context, hint: String, numeric: Boolean): EditText {
        val e = EditText(context)
        e.hint = hint
        e.setHintTextColor(color(context, R.color.text_muted))
        e.setTextColor(color(context, R.color.text_main))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * TEXT_SCALE)
        e.typeface = font(context)
        e.maxLines = 1
        e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        val stroke = ColorUtils.setAlphaComponent(color(context, R.color.text_muted), 90)
        e.background = rounded(color(context, R.color.bg), dp(context, 12).toFloat(), stroke, dp(context, 1))
        e.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        return e
    }

    fun seekBar(context: Context, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val bar = SeekBar(context)
        bar.max = max - min
        bar.progress = value - min
        val primary = color(context, R.color.primary)
        bar.progressTintList = ColorStateList.valueOf(primary)
        bar.thumbTintList = ColorStateList.valueOf(primary)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress + min)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return bar
    }

    /** مربع رنگیِ گرد با آیکن سفید (مثل آیکن‌های پوشه‌ها). */
    fun iconBadge(context: Context, iconRes: Int, bgColor: Int, sizeDp: Int): FrameLayout {
        val box = FrameLayout(context)
        box.background = rounded(bgColor, dp(context, 16).toFloat())
        val img = ImageView(context)
        img.setImageResource(iconRes)
        img.setColorFilter(Color.WHITE)
        val inner = dp(context, sizeDp / 2)
        val lp = FrameLayout.LayoutParams(inner, inner)
        lp.gravity = Gravity.CENTER
        box.addView(img, lp)
        return box
    }

    /** یک ردیف بزرگ و قابل‌لمس (مثل کارت پوشه‌ها). */
    fun menuRow(
        context: Context,
        iconRes: Int,
        iconColor: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = dp(context, 14)
        row.setPadding(pad, pad, pad, pad)
        val radius = dp(context, 18).toFloat()
        row.background = ripple(rounded(color(context, R.color.surface), radius), radius, 0x224F46E5)
        row.elevation = dp(context, 2).toFloat()
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick() }

        row.addView(
            iconBadge(context, iconRes, iconColor, 48),
            LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))
        )

        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.addView(
            text(context, title, 17f, color(context, R.color.text_main), true),
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        val subLp = LinearLayout.LayoutParams(MATCH, WRAP)
        subLp.topMargin = dp(context, 3)
        col.addView(text(context, subtitle, 13f, color(context, R.color.text_muted)), subLp)
        val colLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        colLp.marginStart = dp(context, 14)
        colLp.marginEnd = dp(context, 8)
        row.addView(col, colLp)

        val chevron = ImageView(context)
        chevron.setImageResource(R.drawable.ic_back)
        chevron.rotation = 180f
        chevron.setColorFilter(color(context, R.color.text_muted))
        row.addView(chevron, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)))
        return row
    }
}

/** ردیف انتخاب (چیپ‌ها): فقط یکی انتخاب می‌شود. اگر چیپ‌ها جا نشدند، به‌صورت افقی اسکرول می‌شود. */
class PhotoChips(
    private val context: Context,
    labels: List<String>,
    selectedIndex: Int,
    private val onChange: (Int) -> Unit
) {
    val view: PhotoFlowLayout = PhotoFlowLayout(context)
    private val chips = ArrayList<TextView>()
    var selected: Int = selectedIndex
        private set

    init {
        for (i in labels.indices) {
            val chip = PhotoUi.text(context, labels[i], 14f, Color.BLACK, true)
            chip.gravity = Gravity.CENTER
            chip.setPadding(PhotoUi.dp(context, 14), PhotoUi.dp(context, 9), PhotoUi.dp(context, 14), PhotoUi.dp(context, 9))
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener { select(i, true) }
            chips.add(chip)
            view.addView(chip)
        }
        restyle()
    }

    /** هیچ چیپی انتخاب‌شده نباشد (برای چیپ‌هایی که فقط یک کار سریع انجام می‌دهند). */
    fun clear() {
        selected = -1
        restyle()
    }

    fun select(index: Int, notify: Boolean) {
        if (index < 0 || index >= chips.size) return
        selected = index
        restyle()
        if (notify) onChange(index)
    }

    private fun restyle() {
        val primary = PhotoUi.color(context, R.color.primary)
        val surface = PhotoUi.color(context, R.color.surface)
        val textMain = PhotoUi.color(context, R.color.text_main)
        val stroke = ColorUtils.setAlphaComponent(PhotoUi.color(context, R.color.text_muted), 90)
        val radius = PhotoUi.dp(context, 20).toFloat()
        for (i in chips.indices) {
            val chip = chips[i]
            if (i == selected) {
                chip.setTextColor(Color.WHITE)
                chip.background = PhotoUi.rounded(primary, radius)
            } else {
                chip.setTextColor(textMain)
                chip.background = PhotoUi.rounded(surface, radius, stroke, PhotoUi.dp(context, 1))
            }
        }
    }
}

/* ------------------------------------------------------------------
 *  کار با عکس: خواندن، چرخاندن بر اساس اطلاعات دوربین، کوچک‌کردن، ذخیره با فرمت دلخواه
 * ------------------------------------------------------------------ */
object PhotoImageUtils {

    enum class Fmt(val label: String, val ext: String, val mime: String, val lossy: Boolean) {
        JPEG("JPG", "jpg", "image/jpeg", true),
        PNG("PNG", "png", "image/png", false),
        WEBP("WebP", "webp", "image/webp", true),
        BMP("BMP", "bmp", "image/bmp", false)
    }

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val quality: Int, val reachedTarget: Boolean)

    class SheetPlan(val cols: Int, val rows: Int, val rotated: Boolean)

    /** بیشترین ضلعِ عکسی که برای پردازش در حافظه باز می‌شود (برای جلوگیری از پر شدن حافظه‌ی گوشی). */
    private const val HARD_CAP = 6144

    /* ---------- اعداد ---------- */

    /** عدد صحیح را از متن می‌خواند (ارقام فارسی و عربی را هم می‌فهمد). */
    fun parseInt(raw: String?): Int? {
        val d = parseNumber(raw) ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        if (d > 1.0e9) return null
        return Math.round(d).toInt()
    }

    /** عدد اعشاری را از متن می‌خواند (ارقام فارسی و جداکننده‌ی «٫» یا «/» را هم می‌فهمد). */
    fun parseNumber(raw: String?): Double? {
        if (raw == null) return null
        val sb = StringBuilder()
        var dotUsed = false
        for (ch in raw) {
            if (ch in '0'..'9') {
                sb.append(ch)
            } else if (ch in '\u06F0'..'\u06F9') {
                sb.append('0' + (ch - '\u06F0'))
            } else if (ch in '\u0660'..'\u0669') {
                sb.append('0' + (ch - '\u0660'))
            } else if ((ch == '.' || ch == '\u066B' || ch == '/' || ch == ',') && !dotUsed && sb.isNotEmpty()) {
                sb.append('.')
                dotUsed = true
            }
        }
        if (sb.isEmpty() || sb.toString() == ".") return null
        return sb.toString().toDoubleOrNull()
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "نامشخص"
        if (bytes < 1024L) return "$bytes بایت"
        if (bytes < 1024L * 1024L) return "${Math.round(bytes / 1024.0)} کیلوبایت"
        return String.format(Locale.US, "%.1f", bytes / 1048576.0) + " مگابایت"
    }

    /** نام فایل بدون پسوند. */
    fun baseName(name: String): String {
        val n = name.substringBeforeLast('.', name).trim()
        return if (n.isBlank()) "photo" else n
    }

    /* ---------- خواندن اطلاعات فایل ---------- */

    /** (نام فایل، حجم به بایت) از روی آدرس انتخاب‌شده. */
    fun queryInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )
            if (cursor != null) {
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni) ?: ""
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
        } catch (e: Exception) {
            // مهم نیست
        }
        if (name.isBlank()) name = uri.lastPathSegment ?: "photo"
        return Pair(name, size)
    }

    private fun readOrientation(context: Context, uri: Uri): Int {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ExifInterface.ORIENTATION_NORMAL
            input.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return null
        }
        return m
    }

    /** ابعاد واقعی عکس (عرض، ارتفاع) با در نظر گرفتن چرخش دوربین؛ بدون باز کردن کاملِ عکس. */
    fun readSize(context: Context, uri: Uri): IntArray? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val o = readOrientation(context, uri)
        val swap = o == ExifInterface.ORIENTATION_ROTATE_90 ||
            o == ExifInterface.ORIENTATION_ROTATE_270 ||
            o == ExifInterface.ORIENTATION_TRANSPOSE ||
            o == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swap) intArrayOf(bounds.outHeight, bounds.outWidth) else intArrayOf(bounds.outWidth, bounds.outHeight)
    }

    /**
     * عکس را باز می‌کند و چرخشِ دوربین را اعمال می‌کند.
     * want: کمترین اندازه‌ی ضلع بلندِ مورد نیاز؛ عکس‌های خیلی بزرگ فقط تا حدِ لازم کوچک باز می‌شوند.
     */
    fun decode(context: Context, uri: Uri, want: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val longSide = maxOf(w, h)
        var sample = 1
        while (longSide / sample > HARD_CAP) sample *= 2
        while (longSide / (sample * 2) >= want) sample *= 2

        var bitmap: Bitmap? = null
        var attempts = 0
        while (bitmap == null && attempts < 4) {
            try {
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                val stream = resolver.openInputStream(uri) ?: return null
                bitmap = stream.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bitmap == null) return null
            } catch (e: OutOfMemoryError) {
                sample *= 2
                attempts++
            } catch (e: Exception) {
                return null
            }
        }
        val decoded = bitmap ?: return null
        val matrix = orientationMatrix(readOrientation(context, uri)) ?: return decoded
        return try {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            decoded
        }
    }

    /** باز کردن یک فایل ساخته‌شده (برای پیش‌نمایش). */
    fun decodeFile(file: File, want: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= want) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Throwable) {
            null
        }
    }

    /* ---------- تغییر اندازه و چرخش ---------- */

    /** تغییر اندازه با کیفیت خوب (برای کوچک‌کردن زیاد، مرحله‌به‌مرحله نصف می‌کند). */
    fun resizeSmooth(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val tw = maxOf(1, targetW)
        val th = maxOf(1, targetH)
        if (tw == src.width && th == src.height) return src
        var current = src
        var curW = src.width
        var curH = src.height
        while (curW / 2 >= tw && curH / 2 >= th) {
            val nw = curW / 2
            val nh = curH / 2
            val next = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== src) current.recycle()
            current = next
            curW = nw
            curH = nh
        }
        val result = Bitmap.createScaledBitmap(current, tw, th, true)
        if (current !== src && current !== result) current.recycle()
        return result
    }

    /** چرخش (به‌صورت ربع دور ساعت‌گرد) و آینه کردن. */
    fun transform(src: Bitmap, quarterTurns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0 && !flipH && !flipV) return src
        val m = Matrix()
        if (turns != 0) m.postRotate(turns * 90f)
        if (flipH) m.postScale(-1f, 1f)
        if (flipV) m.postScale(1f, -1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** اگر عکس بخش شفاف داشته باشد، روی زمینه‌ی سفید می‌نشاند (برای فرمت‌هایی که شفافیت ندارند). */
    fun opaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /** ماتریس رنگ برای نور (−۱۰۰ تا ۱۰۰)، کنتراست (−۱۰۰ تا ۱۰۰) و اشباع رنگ (۰ تا ۲۰۰؛ ۱۰۰ یعنی بدون تغییر). */
    fun colorMatrix(brightness: Int, contrast: Int, saturation: Int): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(saturation / 100f)
        val c = (100 + contrast) / 100f
        val t = 128f * (1f - c) + brightness * 1.28f
        val adjust = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(adjust)
        return cm
    }

    /* ---------- ذخیره با فرمت دلخواه ---------- */

    /**
     * عکس را با فرمت و کیفیت داده‌شده در خروجی می‌نویسد. اطلاعات مخفیِ عکس (مثل موقعیت مکانی و
     * مدل گوشی) هیچ‌وقت در خروجی نوشته نمی‌شود.
     */
    @Suppress("DEPRECATION")
    fun encodeTo(src: Bitmap, format: Fmt, quality: Int, out: OutputStream) {
        if (format == Fmt.BMP) {
            writeBmp(src, out)
            return
        }
        val q = quality.coerceIn(1, 100)
        val bmp = if (format == Fmt.JPEG) opaque(src) else src
        val compressFormat = when (format) {
            Fmt.PNG -> Bitmap.CompressFormat.PNG
            Fmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        bmp.compress(compressFormat, q, out)
        if (bmp !== src) bmp.recycle()
    }

    fun encodeBytes(src: Bitmap, format: Fmt, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        encodeTo(src, format, quality, out)
        return out.toByteArray()
    }

    private fun writeInt32(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeInt16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** BMP بدون فشرده‌سازی (۲۴ بیتی)؛ ردیف‌به‌ردیف نوشته می‌شود تا حافظه‌ی زیادی نگیرد. */
    private fun writeBmp(src: Bitmap, out: OutputStream) {
        val bmp = opaque(src)
        val w = bmp.width
        val h = bmp.height
        val rowBytes = (w * 3 + 3) and 3.inv()
        val dataSize = rowBytes * h
        out.write(0x42)
        out.write(0x4D)
        writeInt32(out, 54 + dataSize)
        writeInt16(out, 0)
        writeInt16(out, 0)
        writeInt32(out, 54)
        writeInt32(out, 40)
        writeInt32(out, w)
        writeInt32(out, h)
        writeInt16(out, 1)
        writeInt16(out, 24)
        writeInt32(out, 0)
        writeInt32(out, dataSize)
        writeInt32(out, 2835)
        writeInt32(out, 2835)
        writeInt32(out, 0)
        writeInt32(out, 0)
        val pixels = IntArray(w)
        val row = ByteArray(rowBytes)
        for (y in h - 1 downTo 0) {
            bmp.getPixels(pixels, 0, w, 0, y, w, 1)
            var p = 0
            for (x in 0 until w) {
                val c = pixels[x]
                row[p++] = (c and 0xFF).toByte()
                row[p++] = ((c shr 8) and 0xFF).toByte()
                row[p++] = ((c shr 16) and 0xFF).toByte()
            }
            out.write(row, 0, rowBytes)
        }
        if (bmp !== src) bmp.recycle()
    }

    /**
     * عکس را طوری فشرده می‌کند که حجمش از targetBytes بیشتر نشود (فقط برای JPG و WebP).
     * اول کیفیت کم می‌شود؛ اگر کافی نبود، ابعاد کمی کوچک می‌شود.
     */
    fun encodeToTarget(src: Bitmap, format: Fmt, targetBytes: Long, minQuality: Int): Encoded {
        var work: Bitmap = src
        var owned: Bitmap? = null
        var result: Encoded? = null
        var guard = 0
        while (result == null && guard < 14) {
            guard++
            val top = encodeBytes(work, format, 92)
            if (top.size.toLong() <= targetBytes) {
                result = Encoded(top, work.width, work.height, 92, true)
            } else {
                val low = encodeBytes(work, format, minQuality)
                if (low.size.toLong() <= targetBytes) {
                    var lo = minQuality
                    var hi = 92
                    var best = low
                    var bestQuality = minQuality
                    while (hi - lo > 1) {
                        val mid = (lo + hi) / 2
                        val data = encodeBytes(work, format, mid)
                        if (data.size.toLong() <= targetBytes) {
                            lo = mid
                            best = data
                            bestQuality = mid
                        } else {
                            hi = mid
                        }
                    }
                    result = Encoded(best, work.width, work.height, bestQuality, true)
                } else {
                    // حتی با کمترین کیفیت بزرگ‌تر است: ابعاد را کمی کوچک می‌کنیم و دوباره امتحان می‌کنیم
                    val ratio = targetBytes.toDouble() / low.size.toDouble()
                    var factor = Math.sqrt(ratio) * 0.95
                    if (factor > 0.92) factor = 0.92
                    if (factor < 0.5) factor = 0.5
                    val nw = (work.width * factor).toInt()
                    val nh = (work.height * factor).toInt()
                    if (nw < 48 || nh < 48) {
                        result = Encoded(low, work.width, work.height, minQuality, false)
                    } else {
                        val scaled = resizeSmooth(work, nw, nh)
                        val previous = owned
                        if (previous != null && previous !== scaled) previous.recycle()
                        owned = scaled
                        work = scaled
                    }
                }
            }
        }
        val finalResult = result ?: Encoded(encodeBytes(work, format, minQuality), work.width, work.height, minQuality, false)
        val last = owned
        if (last != null) last.recycle()
        return finalResult
    }

    /** چگالی چاپ (DPI) را داخل فایل JPG می‌نویسد تا اندازه‌ی چاپ دقیق باشد. اگر نشد، فایل دست‌نخورده برمی‌گردد. */
    fun setJpegDpi(jpeg: ByteArray, dpi: Int): ByteArray {
        if (jpeg.size < 20) return jpeg
        fun u(i: Int): Int = jpeg[i].toInt() and 0xFF
        val isJfif = u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF && u(3) == 0xE0 &&
            u(6) == 0x4A && u(7) == 0x46 && u(8) == 0x49 && u(9) == 0x46 && u(10) == 0x00
        if (!isJfif) return jpeg
        val copy = jpeg.copyOf()
        copy[13] = 1
        copy[14] = ((dpi shr 8) and 0xFF).toByte()
        copy[15] = (dpi and 0xFF).toByte()
        copy[16] = ((dpi shr 8) and 0xFF).toByte()
        copy[17] = (dpi and 0xFF).toByte()
        return copy
    }

    /* ---------- برگه‌ی چاپ عکس پرسنلی ---------- */

    /** روی یک برگه، چند ستون و چند ردیف عکس جا می‌شود (عکس را در صورت نیاز می‌چرخاند). */
    fun planSheet(sheetW: Int, sheetH: Int, photoW: Int, photoH: Int, margin: Int, gap: Int): SheetPlan {
        val ac = maxOf(0, (sheetW - 2 * margin + gap) / (photoW + gap))
        val ar = maxOf(0, (sheetH - 2 * margin + gap) / (photoH + gap))
        val bc = maxOf(0, (sheetW - 2 * margin + gap) / (photoH + gap))
        val br = maxOf(0, (sheetH - 2 * margin + gap) / (photoW + gap))
        return if (bc * br > ac * ar) SheetPlan(bc, br, true) else SheetPlan(ac, ar, false)
    }

    /** چند نسخه از عکس را روی یک برگه‌ی سفید می‌چیند. maxCopies=0 یعنی هر چقدر جا شد. */
    fun composeSheet(photo: Bitmap, sheetW: Int, sheetH: Int, margin: Int, gap: Int, maxCopies: Int): Bitmap? {
        val plan = planSheet(sheetW, sheetH, photo.width, photo.height, margin, gap)
        val total = plan.cols * plan.rows
        if (total <= 0) return null
        val tile = if (plan.rotated) transform(photo, 1, false, false) else photo
        val pw = tile.width
        val ph = tile.height
        val count = if (maxCopies > 0) minOf(maxCopies, total) else total
        val usedCols = minOf(plan.cols, count)
        val usedRows = (count + plan.cols - 1) / plan.cols
        val gridW = usedCols * pw + (usedCols - 1) * gap
        val gridH = usedRows * ph + (usedRows - 1) * gap
        val startX = (sheetW - gridW) / 2
        val startY = (sheetH - gridH) / 2
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        sheet.setHasAlpha(false)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        for (i in 0 until count) {
            val r = i / plan.cols
            val c = i % plan.cols
            val x = startX + c * (pw + gap)
            val y = startY + r * (ph + gap)
            canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
        }
        if (tile !== photo) tile.recycle()
        return sheet
    }

    /** چند نسخه روی برگه جا می‌شود (برای نمایش به کاربر). */
    fun sheetCapacity(sheetW: Int, sheetH: Int, photoW: Int, photoH: Int, margin: Int, gap: Int): Int {
        val plan = planSheet(sheetW, sheetH, photoW, photoH, margin, gap)
        return plan.cols * plan.rows
    }
}

/* ------------------------------------------------------------------
 *  فایل‌های موقت، ذخیره در گوشی و اشتراک‌گذاری
 * ------------------------------------------------------------------ */
object PhotoFiles {

    fun workDir(context: Context): File {
        val dir = File(context.cacheDir, "share/photo_tools")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** خروجی‌های دفعه‌ی قبل را پاک می‌کند (فقط پوشه‌ی موقتِ همین ابزارها). */
    fun clearWorkDir(context: Context) {
        try {
            val files = File(context.cacheDir, "share/photo_tools").listFiles()
            if (files != null) {
                for (f in files) f.delete()
            }
        } catch (e: Exception) {
            // مهم نیست
        }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimEnd('.')
        return cleaned.ifBlank { "photo" }.take(100)
    }

    /** فایل جدید و بدون تکرار در پوشه‌ی موقت. */
    fun newFile(context: Context, name: String): File {
        val dir = workDir(context)
        val safe = safeName(name)
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val base = safe.substringBeforeLast('.', safe)
        val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, base + " (" + i + ")" + ext)
            i++
        }
        return candidate
    }

    /** فایل را در پوشه‌ی Downloads/آسان نت ذخیره می‌کند (همان جایی که بقیه‌ی دانلودهای برنامه می‌روند). */
    fun saveToDownloads(context: Context, file: File, displayName: String, mime: String): Boolean {
        return try {
            val target = AppDownloadStorage.create(context, AppDownloadStorage.sanitize(displayName), mime)
            try {
                file.inputStream().use { input ->
                    AppDownloadStorage.openOutput(context, target, false).use { output ->
                        input.copyTo(output)
                    }
                }
                AppDownloadStorage.finish(context, target, mime) { }
                true
            } catch (e: Exception) {
                AppDownloadStorage.delete(context, target)
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun share(activity: Activity, files: List<File>, mime: String) {
        if (files.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            for (f in files) {
                uris.add(FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f))
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
        } catch (e: Exception) {
            Toast.makeText(activity, "اشتراک‌گذاری ممکن نشد", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * فایل را در گالریِ گوشی (پوشه‌ی Pictures/آسان نت) ذخیره می‌کند، درست مثل عکسی که با دوربین گرفته شده.
     * آدرس گالری برگردانده می‌شود؛ اگر نشد (مثلاً اندروید ۹ و پایین‌تر) null برمی‌گردد.
     */
    fun saveToGallery(context: Context, file: File, displayName: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName(displayName))
            values.put(MediaStore.Images.Media.MIME_TYPE, mime)
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/آسان نت")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val output = resolver.openOutputStream(uri)
                if (output == null) {
                    resolver.delete(uri, null, null)
                    return null
                }
                output.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                val done = ContentValues()
                done.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, done, null, null)
                uri
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Exception) {
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** چند آدرس (مثلاً آدرس‌های گالری) را با برنامه‌های دیگر به اشتراک می‌گذارد. */
    fun shareUris(activity: Activity, uris: List<Uri>, mime: String) {
        if (uris.isEmpty()) return
        try {
            val list = ArrayList<Uri>(uris)
            val intent = if (list.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, list[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
        } catch (e: Exception) {
            Toast.makeText(activity, "اشتراک‌گذاری ممکن نشد", Toast.LENGTH_SHORT).show()
        }
    }

    /** فایل را با برنامه‌ی مناسب گوشی باز می‌کند (مثلاً PDF). */
    fun view(activity: Activity, file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "برنامه‌ای برای باز کردن این فایل روی گوشی پیدا نشد؛ آن را ذخیره کنید", Toast.LENGTH_LONG).show()
        }
    }

    /** اگر همه‌ی فایل‌ها یک نوع باشند همان نوع، وگرنه نوع کلی. */
    fun commonMime(results: List<PhotoResult>): String {
        if (results.isEmpty()) return "*/*"
        val first = results[0].mime
        for (r in results) {
            if (r.mime != first) return "*/*"
        }
        return first
    }
}

/* ------------------------------------------------------------------
 *  ساخت فایل PDF (عکس‌ها مستقیم و بدون افت کیفیت داخل PDF قرار می‌گیرند → حجم کم)
 * ------------------------------------------------------------------ */
class PhotoPdfWriter(private val out: OutputStream) {

    private var position = 0L
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3

    private fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    private fun writeText(text: String) {
        writeBytes(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v)

    fun begin() {
        writeText("%PDF-1.4\n")
        writeBytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
    }

    /** یک صفحه با یک عکس JPG؛ (x, y) گوشه‌ی پایین‌چپِ عکس روی صفحه است (واحد: پوینت). */
    fun addJpegPage(
        jpeg: ByteArray,
        imageW: Int,
        imageH: Int,
        pageW: Float,
        pageH: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ) {
        val imgId = nextId++
        val contentId = nextId++
        val pageId = nextId++

        offsets[imgId] = position
        writeText(
            "$imgId 0 obj\n<< /Type /XObject /Subtype /Image /Width $imageW /Height $imageH " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        )
        writeBytes(jpeg)
        writeText("\nendstream\nendobj\n")

        val content = "q " + num(w) + " 0 0 " + num(h) + " " + num(x) + " " + num(y) + " cm /Im0 Do Q\n"
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        offsets[contentId] = position
        writeText("$contentId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
        writeBytes(contentBytes)
        writeText("endstream\nendobj\n")

        offsets[pageId] = position
        writeText(
            "$pageId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(pageW) + " " + num(pageH) + "] " +
                "/Resources << /XObject << /Im0 $imgId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n"
        )
        pageIds.add(pageId)
    }

    fun finish() {
        offsets[2] = position
        val kids = StringBuilder()
        for (id in pageIds) kids.append(id).append(" 0 R ")
        writeText("2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageIds.size + " >>\nendobj\n")
        offsets[1] = position
        writeText("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val xrefPosition = position
        val total = nextId
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(total).append("\n")
        sb.append("0000000000 65535 f \n")
        for (id in 1 until total) {
            val off = offsets[id] ?: 0L
            sb.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        sb.append("trailer\n<< /Size ").append(total).append(" /Root 1 0 R >>\nstartxref\n")
        sb.append(xrefPosition).append("\n%%EOF\n")
        writeText(sb.toString())
        out.flush()
    }
}

/* ------------------------------------------------------------------
 *  صفحه‌ی پایه‌ی همه‌ی ابزارها: نوار بالا (برگشت + عنوان) و بدنه‌ی اسکرول‌شونده
 * ------------------------------------------------------------------ */
abstract class PhotoToolActivity : AppCompatActivity() {

    protected lateinit var content: LinearLayout

    /** بخش ثابتِ بالای صفحه (پیش‌نمایش عکس)؛ با اسکرول‌کردنِ تنظیمات پایین نمی‌رود. */
    protected lateinit var pinned: LinearLayout

    protected abstract fun toolTitle(): String

    protected abstract fun buildContent(container: LinearLayout)

    protected fun dp(v: Int): Int = PhotoUi.dp(this, v)

    /** ارتفاعِ بخش ثابت به‌صورت درصدی از ارتفاع صفحه (با حداقل و حداکثر بر حسب dp). */
    protected fun pinnedHeight(fraction: Float, minDp: Int, maxDp: Int): Int {
        val h = (resources.displayMetrics.heightPixels * fraction).toInt()
        return h.coerceIn(dp(minDp), dp(maxDp))
    }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    protected fun toastLong(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    protected open fun handleBack() {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhotoFiles.clearWorkDir(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(PhotoUi.color(this, R.color.bg))
        root.addView(buildHeader(), LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        pinned = LinearLayout(this)
        pinned.orientation = LinearLayout.VERTICAL
        pinned.setPadding(dp(16), dp(8), dp(16), dp(8))
        pinned.setBackgroundColor(PhotoUi.color(this, R.color.bg))
        pinned.elevation = dp(4).toFloat()
        pinned.visibility = View.GONE
        root.addView(pinned, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(14), dp(16), dp(28))
        scroll.addView(content, ViewGroup.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(PhotoUi.MATCH, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        buildContent(content)
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundResource(R.drawable.header_gradient)
        bar.setPadding(dp(16), dp(20), dp(16), dp(14))

        val back = ImageView(this)
        back.setImageResource(R.drawable.ic_back)
        back.setPadding(dp(6), dp(6), dp(6), dp(6))
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        if (outValue.resourceId != 0) back.setBackgroundResource(outValue.resourceId)
        back.setOnClickListener { handleBack() }
        bar.addView(back, LinearLayout.LayoutParams(dp(34), dp(34)))

        val title = PhotoUi.text(this, toolTitle(), 20f, Color.WHITE, true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val lp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        lp.marginStart = dp(12)
        bar.addView(title, lp)
        return bar
    }

    /** یک کارت سفید به انتهای صفحه اضافه می‌کند؛ اگر عنوان داده شود بالای کارت نوشته می‌شود. */
    protected fun newCard(parent: LinearLayout, title: String?): LinearLayout {
        val card = PhotoUi.card(this)
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.bottomMargin = dp(12)
        parent.addView(card, lp)
        if (title != null) {
            val t = PhotoUi.text(this, title, 16f, PhotoUi.color(this, R.color.text_main), true)
            val tlp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            tlp.bottomMargin = dp(8)
            card.addView(t, tlp)
        }
        return card
    }

    /** برچسب کوچکِ بالای یک گزینه. */
    protected fun addLabel(parent: LinearLayout, label: String, topMarginDp: Int) {
        val t = PhotoUi.text(this, label, 14f, PhotoUi.color(this, R.color.text_muted), true)
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.topMargin = dp(topMarginDp)
        lp.bottomMargin = dp(6)
        parent.addView(t, lp)
    }

    protected fun addNote(parent: LinearLayout, note: String, topMarginDp: Int) {
        val t = PhotoUi.text(this, note, 12.5f, PhotoUi.color(this, R.color.text_muted))
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.topMargin = dp(topMarginDp)
        parent.addView(t, lp)
    }

    protected fun addChips(parent: LinearLayout, labels: List<String>, selected: Int, onChange: (Int) -> Unit): PhotoChips {
        val chips = PhotoChips(this, labels, selected, onChange)
        parent.addView(chips.view, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        return chips
    }

    private val galleryUris = HashMap<String, Uri>()

    /**
     * نتیجه‌ها را در گالریِ گوشی (Pictures/آسان نت) ذخیره می‌کند و آدرس‌های گالری را برمی‌گرداند.
     * اگر گوشی اجازه ندهد (اندروید ۹ و پایین‌تر)، فهرستِ خالی برمی‌گردد.
     */
    fun saveResultsToGallery(list: List<PhotoResult>, showMessage: Boolean, onDone: (List<Uri>) -> Unit) {
        if (list.isEmpty()) return
        if (!DownloadHelper.ensureStoragePermission(this)) {
            toastLong("برای ذخیره، اجازه‌ی حافظه را تأیید کنید و دوباره بزنید")
            return
        }
        lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) {
                val out = ArrayList<Uri>()
                for (r in list) {
                    val key = r.file.absolutePath
                    val known = galleryUris[key]
                    if (known != null) {
                        out.add(known)
                    } else {
                        val u = PhotoFiles.saveToGallery(this@PhotoToolActivity, r.file, r.displayName, r.mime)
                        if (u != null) {
                            galleryUris[key] = u
                            out.add(u)
                        }
                    }
                }
                out
            }
            if (showMessage) {
                if (uris.isNotEmpty()) {
                    toastLong(uris.size.toString() + " فایل در گالری (پوشه‌ی Pictures/آسان نت) ذخیره شد")
                } else {
                    toastLong("ذخیره در گالری انجام نشد")
                }
            }
            onDone(uris)
        }
    }

    /** چند فایل را در پوشه‌ی Downloads/آسان نت ذخیره می‌کند. */
    fun saveResults(list: List<PhotoResult>, onDone: (Int) -> Unit) {
        if (list.isEmpty()) return
        if (!DownloadHelper.ensureStoragePermission(this)) {
            toastLong("برای ذخیره، اجازه‌ی حافظه را تأیید کنید و دوباره روی «ذخیره» بزنید")
            return
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                var n = 0
                for (r in list) {
                    if (PhotoFiles.saveToDownloads(this@PhotoToolActivity, r.file, r.displayName, r.mime)) n++
                }
                n
            }
            if (saved > 0) {
                toastLong("$saved فایل در پوشه‌ی Downloads/آسان نت ذخیره شد")
            } else {
                toastLong("ذخیره‌سازی انجام نشد")
            }
            onDone(saved)
        }
    }
}

/* ------------------------------------------------------------------
 *  پنل نتیجه‌ی تکی (پیش‌نمایش + ذخیره + اشتراک‌گذاری)؛ برای عکس پرسنلی و ویرایشگر
 * ------------------------------------------------------------------ */
class PhotoResultPanel(private val activity: PhotoToolActivity, parent: LinearLayout) {

    val view: LinearLayout = PhotoUi.card(activity)
    private val preview = ImageView(activity)
    private val info: TextView
    private val saveButton: TextView
    private var current: PhotoResult? = null

    init {
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.bottomMargin = PhotoUi.dp(activity, 12)
        parent.addView(view, lp)

        view.addView(
            PhotoUi.text(activity, "نتیجه", 16f, PhotoUi.color(activity, R.color.text_main), true),
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )

        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        preview.adjustViewBounds = true
        preview.background = PhotoUi.rounded(PhotoUi.color(activity, R.color.bg), PhotoUi.dp(activity, 12).toFloat())
        preview.setPadding(PhotoUi.dp(activity, 6), PhotoUi.dp(activity, 6), PhotoUi.dp(activity, 6), PhotoUi.dp(activity, 6))
        val plp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.dp(activity, 300))
        plp.topMargin = PhotoUi.dp(activity, 8)
        view.addView(preview, plp)

        info = PhotoUi.text(activity, "", 13f, PhotoUi.color(activity, R.color.text_muted))
        val ilp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        ilp.topMargin = PhotoUi.dp(activity, 8)
        view.addView(info, ilp)

        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        saveButton = PhotoUi.button(activity, "ذخیره در گوشی", true) { saveNow() }
        val shareButton = PhotoUi.button(activity, "اشتراک‌گذاری", false) { shareNow() }
        val b1 = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        b1.marginEnd = PhotoUi.dp(activity, 8)
        buttons.addView(saveButton, b1)
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        val blp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        blp.topMargin = PhotoUi.dp(activity, 12)
        view.addView(buttons, blp)

        view.visibility = View.GONE
    }

    fun hide() {
        view.visibility = View.GONE
        current = null
    }

    fun show(result: PhotoResult, infoText: String) {
        current = result
        info.text = infoText
        saveButton.text = "ذخیره در گوشی"
        view.visibility = View.VISIBLE
        preview.setImageDrawable(null)
        activity.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decodeFile(result.file, 1200) }
            if (bmp != null && current === result) preview.setImageBitmap(bmp)
        }
        view.post {
            val parentView = view.parent
            if (parentView is View) {
                // کمی اسکرول تا نتیجه دیده شود
                view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false)
            }
        }
    }

    private fun saveNow() {
        val r = current ?: return
        activity.saveResults(listOf(r)) { n ->
            if (n > 0) saveButton.text = "ذخیره شد"
        }
    }

    private fun shareNow() {
        val r = current ?: return
        PhotoFiles.share(activity, listOf(r.file), r.mime)
    }
}

/* ------------------------------------------------------------------
 *  صفحه‌ی پایه‌ی ابزارهای «چند عکس» (تغییر اندازه، تبدیل فرمت، PDF، واترمارک):
 *  انتخاب عکس‌ها ← تنظیمات ← شروع ← نتیجه (ذخیره/اشتراک)
 * ------------------------------------------------------------------ */
abstract class PhotoBatchActivity<O : Any> : PhotoToolActivity() {

    companion object {
        const val MAX_PHOTOS = 40
    }

    protected val sources = ArrayList<PhotoSource>()
    private val results = ArrayList<PhotoResult>()
    private val thumbs = HashMap<Uri, Bitmap>()
    private var running = false
    private var saved = false

    private lateinit var listBox: LinearLayout
    private lateinit var countText: TextView
    private lateinit var actionButton: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var basePreviewImage: ImageView
    private lateinit var basePlanText: TextView
    private var basePreviewUri: Uri? = null
    private var basePreviewToken = 0
    private var planReady = false

    /** آیا کاربر بتواند ترتیب عکس‌ها را عوض کند (برای PDF). */
    protected open val allowReorder: Boolean = false

    /** پیش‌نمایشِ ثابتِ بالای صفحه (اولین عکس + توضیح خروجی)؛ ابزارهایی که پیش‌نمایش خودشان را دارند خاموشش می‌کنند. */
    protected open val usesDefaultPreview: Boolean = true

    /** اندازه‌ی واقعیِ اولین عکس (عرض، ارتفاع)؛ برای توضیح خروجی. */
    protected var firstPhotoSize: IntArray? = null

    /** یک خط توضیح درباره‌ی خروجی (بر اساس تنظیمات فعلی) که زیر پیش‌نمایش نوشته می‌شود. */
    protected open fun describePlan(): String = ""

    /** بعد از هر تغییرِ تنظیمات صدا زده می‌شود تا توضیح خروجی به‌روز شود. */
    protected fun refreshPlan() {
        if (!usesDefaultPreview || !planReady) return
        if (!::basePlanText.isInitialized) return
        basePlanText.text = describePlan()
    }

    protected abstract fun actionLabel(): String

    protected abstract fun buildOptions(container: LinearLayout)

    /** تنظیمات را از صفحه می‌خواند؛ اگر نامعتبر بود پیام می‌دهد و null برمی‌گرداند. */
    protected abstract fun readOptions(): O?

    /** یک عکس را پردازش می‌کند (روی ترد پس‌زمینه اجرا می‌شود). */
    protected abstract fun processOne(source: PhotoSource, options: O, index: Int): PhotoResult?

    /** وقتی فهرست عکس‌ها تغییر کرد. */
    protected open fun onSourcesChanged() {}

    protected open fun resultHint(): String = ""

    /** نوشته‌ی دکمه‌ی ذخیره. */
    protected open fun saveLabel(): String = "ذخیره در گوشی"

    /** ذخیره‌ی خروجی‌ها (پیش‌فرض: پوشه‌ی Downloads/آسان نت). */
    protected open fun performSave(list: List<PhotoResult>, onDone: (Int) -> Unit) {
        saveResults(list, onDone)
    }

    /** اشتراک‌گذاری خروجی‌ها. */
    protected open fun performShare(list: List<PhotoResult>) {
        PhotoFiles.share(this, list.map { it.file }, PhotoFiles.commonMime(list))
    }

    /** همه‌ی عکس‌ها را پردازش می‌کند (روی ترد پس‌زمینه). برای ابزارهایی با یک خروجی، این تابع عوض می‌شود. */
    protected open fun produce(
        list: List<PhotoSource>,
        options: O,
        progress: (Int, String) -> Unit
    ): BatchOutcome {
        val out = ArrayList<PhotoResult>()
        var failed = 0
        for (i in list.indices) {
            progress(i, list[i].name)
            val r = try {
                processOne(list[i], options, i)
            } catch (t: Throwable) {
                null
            }
            if (r != null) out.add(r) else failed++
        }
        return BatchOutcome(out, failed)
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        addSources(uris)
    }

    override fun buildContent(container: LinearLayout) {
        val textMain = PhotoUi.color(this, R.color.text_main)
        val muted = PhotoUi.color(this, R.color.text_muted)

        if (usesDefaultPreview) {
            basePreviewImage = ImageView(this)
            basePreviewImage.scaleType = ImageView.ScaleType.FIT_CENTER
            basePreviewImage.background = PhotoUi.rounded(PhotoUi.color(this, R.color.surface), dp(14).toFloat())
            pinned.addView(basePreviewImage, LinearLayout.LayoutParams(PhotoUi.MATCH, pinnedHeight(0.2f, 110, 200)))
            basePlanText = PhotoUi.text(this, "", 13f, textMain, true)
            val planLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            planLp.topMargin = dp(6)
            pinned.addView(basePlanText, planLp)
        }

        // کارت عکس‌ها
        val photosCard = newCard(container, null)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(
            PhotoUi.text(this, "عکس‌ها", 16f, textMain, true),
            LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        )
        countText = PhotoUi.text(this, "", 13f, muted)
        head.addView(countText, LinearLayout.LayoutParams(PhotoUi.WRAP, PhotoUi.WRAP))
        photosCard.addView(head, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val listLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        listLp.topMargin = dp(8)
        photosCard.addView(listBox, listLp)

        val addButton = PhotoUi.button(this, "+  افزودن عکس از گالری", false) { openPicker() }
        val addLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        addLp.topMargin = dp(10)
        photosCard.addView(addButton, addLp)

        // تنظیمات هر ابزار
        buildOptions(container)

        actionButton = PhotoUi.button(this, actionLabel(), true) { startProcessing() }
        val actionLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        actionLp.bottomMargin = dp(12)
        container.addView(actionButton, actionLp)

        // نوار پیشرفت
        progressCard = newCard(container, null)
        progressText = PhotoUi.text(this, "", 14f, textMain, true)
        progressCard.addView(progressText, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.progressTintList = ColorStateList.valueOf(PhotoUi.color(this, R.color.primary))
        val pbLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        pbLp.topMargin = dp(10)
        progressCard.addView(progressBar, pbLp)
        progressCard.visibility = View.GONE

        // نتیجه
        resultCard = newCard(container, null)
        resultCard.visibility = View.GONE

        planReady = true
        refreshSources()
    }

    private fun openPicker() {
        if (running) return
        pickImages.launch("image/*")
    }

    private fun addSources(uris: List<Uri>) {
        var added = 0
        for (uri in uris) {
            if (sources.size >= MAX_PHOTOS) {
                toast("حداکثر $MAX_PHOTOS عکس در هر بار")
                break
            }
            var exists = false
            for (s in sources) {
                if (s.uri == uri) exists = true
            }
            if (exists) continue
            val info = PhotoImageUtils.queryInfo(this, uri)
            sources.add(PhotoSource(uri, info.first, info.second))
            added++
        }
        if (added > 0) {
            clearResults()
            refreshSources()
            onSourcesChanged()
        }
    }

    private fun removeSource(index: Int) {
        if (running || index < 0 || index >= sources.size) return
        sources.removeAt(index)
        clearResults()
        refreshSources()
        onSourcesChanged()
    }

    private fun moveSource(index: Int, delta: Int) {
        if (running) return
        val target = index + delta
        if (index < 0 || index >= sources.size || target < 0 || target >= sources.size) return
        val item = sources.removeAt(index)
        sources.add(target, item)
        refreshSources()
        onSourcesChanged()
    }

    private fun clearResults() {
        results.clear()
        saved = false
        resultCard.removeAllViews()
        resultCard.visibility = View.GONE
    }

    private fun refreshSources() {
        listBox.removeAllViews()
        countText.text = if (sources.isEmpty()) "" else "${sources.size} عکس"
        updateBasePreview()
        if (sources.isEmpty()) {
            val empty = PhotoUi.text(
                this,
                "هنوز عکسی انتخاب نشده است. روی «افزودن عکس» بزنید.",
                14f,
                PhotoUi.color(this, R.color.text_muted)
            )
            empty.setPadding(0, dp(8), 0, dp(4))
            listBox.addView(empty, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
            return
        }
        for (i in sources.indices) {
            listBox.addView(buildSourceRow(i, sources[i]), LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        }
    }

    /** اولین عکس را بالای صفحه ثابت نگه می‌دارد تا هنگام اسکرول تنظیمات دیده شود. */
    private fun updateBasePreview() {
        if (!usesDefaultPreview) return
        if (sources.isEmpty()) {
            pinned.visibility = View.GONE
            basePreviewUri = null
            firstPhotoSize = null
            return
        }
        pinned.visibility = View.VISIBLE
        val first = sources[0]
        if (basePreviewUri == first.uri) {
            refreshPlan()
            return
        }
        basePreviewUri = first.uri
        basePreviewToken++
        val token = basePreviewToken
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoBatchActivity, first.uri, 700) }
            val size = withContext(Dispatchers.IO) { PhotoImageUtils.readSize(this@PhotoBatchActivity, first.uri) }
            if (token == basePreviewToken) {
                firstPhotoSize = size
                if (bmp != null) basePreviewImage.setImageBitmap(bmp)
                refreshPlan()
            }
        }
    }

    private fun smallAction(label: String, onClick: () -> Unit): TextView {
        val t = PhotoUi.text(this, label, 16f, PhotoUi.color(this, R.color.text_muted), true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(6), dp(8), dp(6))
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildSourceRow(index: Int, src: PhotoSource): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))

        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = PhotoUi.rounded(PhotoUi.color(this, R.color.bg), dp(10).toFloat())
        val cached = thumbs[src.uri]
        if (cached != null) {
            thumb.setImageBitmap(cached)
        } else {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoBatchActivity, src.uri, 160) }
                if (bmp != null) {
                    thumbs[src.uri] = bmp
                    thumb.setImageBitmap(bmp)
                }
            }
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(54), dp(54)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = PhotoUi.text(this, src.name, 14f, PhotoUi.color(this, R.color.text_main), true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        col.addView(
            PhotoUi.text(this, "حجم: " + PhotoImageUtils.formatSize(src.sizeBytes), 12.5f, PhotoUi.color(this, R.color.text_muted)),
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        val colLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        colLp.marginStart = dp(10)
        colLp.marginEnd = dp(4)
        row.addView(col, colLp)

        if (allowReorder) {
            row.addView(smallAction("▲") { moveSource(index, -1) }, LinearLayout.LayoutParams(PhotoUi.WRAP, PhotoUi.WRAP))
            row.addView(smallAction("▼") { moveSource(index, 1) }, LinearLayout.LayoutParams(PhotoUi.WRAP, PhotoUi.WRAP))
        }
        row.addView(smallAction("✕") { removeSource(index) }, LinearLayout.LayoutParams(PhotoUi.WRAP, PhotoUi.WRAP))
        return row
    }

    private fun setActionEnabled(enabled: Boolean) {
        actionButton.alpha = if (enabled) 1f else 0.5f
        actionButton.isEnabled = enabled
    }

    private fun startProcessing() {
        if (running) return
        if (sources.isEmpty()) {
            toast("ابتدا حداقل یک عکس انتخاب کنید")
            return
        }
        val options = readOptions() ?: return
        val snapshot = ArrayList<PhotoSource>(sources)
        running = true
        setActionEnabled(false)
        clearResults()
        progressCard.visibility = View.VISIBLE
        progressBar.max = snapshot.size
        progressBar.progress = 0
        progressText.text = "در حال آماده‌سازی..."
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    produce(snapshot, options) { done, name ->
                        runOnUiThread {
                            progressBar.progress = done
                            progressText.text = "در حال پردازش (" + (done + 1) + " از " + snapshot.size + "): " + name
                        }
                    }
                } catch (t: Throwable) {
                    BatchOutcome(emptyList(), snapshot.size)
                }
            }
            running = false
            setActionEnabled(true)
            progressCard.visibility = View.GONE
            showResults(outcome)
        }
    }

    private fun showResults(outcome: BatchOutcome) {
        results.clear()
        results.addAll(outcome.results)
        saved = false
        resultCard.removeAllViews()
        resultCard.visibility = View.VISIBLE

        val textMain = PhotoUi.color(this, R.color.text_main)
        val muted = PhotoUi.color(this, R.color.text_muted)
        val title = if (outcome.results.isEmpty()) "ساخت خروجی ممکن نشد" else "نتیجه آماده است"
        resultCard.addView(PhotoUi.text(this, title, 16f, textMain, true), LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        if (outcome.failed > 0) {
            val warn = PhotoUi.text(
                this,
                outcome.failed.toString() + " مورد پردازش نشد (فایل خراب یا حافظه‌ی کم).",
                13f,
                PhotoUi.color(this, R.color.danger)
            )
            val wlp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            wlp.topMargin = dp(6)
            resultCard.addView(warn, wlp)
        }
        if (outcome.results.isEmpty()) return

        for (r in outcome.results) {
            val rowLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            rowLp.topMargin = dp(6)
            resultCard.addView(buildResultRow(r), rowLp)
        }

        val hint = resultHint()
        if (hint.isNotBlank()) {
            val hlp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            hlp.topMargin = dp(8)
            resultCard.addView(PhotoUi.text(this, hint, 12.5f, muted), hlp)
        }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val saveButton = PhotoUi.button(this, saveLabel(), true) { }
        saveButton.setOnClickListener {
            performSave(results) { n ->
                if (n > 0) {
                    saved = true
                    saveButton.text = "ذخیره شد"
                }
            }
        }
        val shareButton = PhotoUi.button(this, "اشتراک‌گذاری", false) {
            performShare(results)
        }
        val b1 = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        b1.marginEnd = dp(8)
        buttons.addView(saveButton, b1)
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f))
        val blp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        blp.topMargin = dp(12)
        resultCard.addView(buttons, blp)
    }

    private fun buildResultRow(r: PhotoResult): View {
        val textMain = PhotoUi.color(this, R.color.text_main)
        val muted = PhotoUi.color(this, R.color.text_muted)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))

        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = PhotoUi.rounded(PhotoUi.color(this, R.color.bg), dp(10).toFloat())
        if (r.mime.startsWith("image/")) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decodeFile(r.file, 160) }
                if (bmp != null) thumb.setImageBitmap(bmp)
            }
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(54), dp(54)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = PhotoUi.text(this, r.displayName, 14f, textMain, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        val after = r.file.length()
        var line = if (r.sourceBytes > 0) {
            "حجم: " + PhotoImageUtils.formatSize(r.sourceBytes) + " به " + PhotoImageUtils.formatSize(after)
        } else {
            "حجم: " + PhotoImageUtils.formatSize(after)
        }
        if (r.sourceBytes > 0 && after < r.sourceBytes) {
            val pct = 100 - (after * 100 / r.sourceBytes).toInt()
            if (pct > 0) line += " (" + pct + "٪ کمتر)"
        }
        col.addView(PhotoUi.text(this, line, 12.5f, muted), LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        if (r.width > 0 && r.height > 0) {
            col.addView(
                PhotoUi.text(this, "ابعاد: " + r.width + " در " + r.height + " پیکسل", 12.5f, muted),
                LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            )
        }
        if (r.note.isNotBlank()) {
            col.addView(
                PhotoUi.text(this, r.note, 12.5f, PhotoUi.color(this, R.color.danger)),
                LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
            )
        }
        val colLp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
        colLp.marginStart = dp(10)
        row.addView(col, colLp)

        if (r.mime == "application/pdf") {
            val openButton = smallAction("باز کردن") { PhotoFiles.view(this, r.file, r.mime) }
            openButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * PhotoUi.TEXT_SCALE)
            row.addView(openButton, LinearLayout.LayoutParams(PhotoUi.WRAP, PhotoUi.WRAP))
        }
        return row
    }
}
