package com.kafinet.asannet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ===================================================================
 *  ساخت استیکر (ایتا، تلگرام)
 *  خروجی: فایل WebP که یک ضلعش دقیقاً ۵۱۲ پیکسل و ضلع دیگرش حداکثر ۵۱۲ است، حجمش از ۵۱۲ کیلوبایت
 *  بیشتر نمی‌شود و جاهای خالی‌اش شفاف است؛ همان قواعدی که پیام‌رسان‌ها برای استیکر می‌خواهند.
 * =================================================================== */

/** تنظیمات ساخت استیکر. */
class StickerOptions(
    val square: Boolean,
    val fill: Boolean,
    val removeBg: Boolean,
    val tolerance: Int,
    val outline: Boolean
)

object PhotoSticker {

    const val SIZE = 512
    const val MAX_BYTES = 512L * 1024L

    /** عکس را به یک استیکر ۵۱۲ پیکسلی تبدیل می‌کند (عکس اصلی دست‌نخورده می‌ماند). */
    fun render(src: Bitmap, o: StickerOptions): Bitmap {
        val out: Bitmap
        var regionL = 0
        var regionT = 0
        val regionR: Int
        val regionB: Int

        if (o.square && o.fill) {
            // پر کردن کادر: از وسطِ عکس یک مربع بریده می‌شود
            val side = minOf(src.width, src.height)
            val cx = (src.width - side) / 2
            val cy = (src.height - side) / 2
            val cropped = if (side == src.width && side == src.height) src else Bitmap.createBitmap(src, cx, cy, side, side)
            val scaled = PhotoImageUtils.resizeSmooth(cropped, SIZE, SIZE)
            out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== src) cropped.recycle()
            regionR = SIZE
            regionB = SIZE
        } else {
            val s = SIZE.toFloat() / maxOf(src.width, src.height)
            val drawW = maxOf(1, Math.round(src.width * s))
            val drawH = maxOf(1, Math.round(src.height * s))
            val scaled = PhotoImageUtils.resizeSmooth(src, drawW, drawH)
            val canvasW = if (o.square) SIZE else drawW
            val canvasH = if (o.square) SIZE else drawH
            out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val left = (canvasW - drawW) / 2
            val top = (canvasH - drawH) / 2
            val canvas = Canvas(out)
            canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
            if (scaled !== src) scaled.recycle()
            regionL = left
            regionT = top
            regionR = left + drawW
            regionB = top + drawH
        }

        if (o.removeBg) removeBackground(out, regionL, regionT, regionR, regionB, o.tolerance)
        if (o.outline) addOutline(out, 4)
        return out
    }

    /**
     * فایل نهایی WebP: کیفیت آن‌قدر کم می‌شود که حجم از targetBytes بیشتر نشود؛
     * ابعاد هرگز تغییر نمی‌کند (چون باید دقیقاً ۵۱۲ بماند).
     */
    fun encodeWithinSize(bmp: Bitmap, targetBytes: Long): PhotoImageUtils.Encoded {
        val fmt = PhotoImageUtils.Fmt.WEBP
        val high = PhotoImageUtils.encodeBytes(bmp, fmt, 95)
        if (high.size.toLong() <= targetBytes) {
            return PhotoImageUtils.Encoded(high, bmp.width, bmp.height, 95, true)
        }
        var lo = 10
        var hi = 95
        val low = PhotoImageUtils.encodeBytes(bmp, fmt, lo)
        if (low.size.toLong() > targetBytes) {
            return PhotoImageUtils.Encoded(low, bmp.width, bmp.height, lo, false)
        }
        var best = low
        var bestQuality = lo
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            val data = PhotoImageUtils.encodeBytes(bmp, fmt, mid)
            if (data.size.toLong() <= targetBytes) {
                lo = mid
                best = data
                bestQuality = mid
            } else {
                hi = mid
            }
        }
        return PhotoImageUtils.Encoded(best, bmp.width, bmp.height, bestQuality, true)
    }

    private fun isBackground(c: Int, refs: IntArray, count: Int, tol: Int): Boolean {
        if ((c ushr 24) < 128) return true
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        for (k in 0 until count) {
            val rc = refs[k]
            if (Math.abs(r - ((rc shr 16) and 0xFF)) <= tol &&
                Math.abs(g - ((rc shr 8) and 0xFF)) <= tol &&
                Math.abs(b - (rc and 0xFF)) <= tol
            ) {
                return true
            }
        }
        return false
    }

    /**
     * پس‌زمینه‌ی ساده را از لبه‌های عکس شروع می‌کند به پاک‌کردن و تا جایی ادامه می‌دهد که رنگ
     * شبیه رنگِ گوشه‌ها باشد. فقط به‌درد پس‌زمینه‌ی یکدست و ساده می‌خورد.
     */
    private fun removeBackground(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, tol: Int) {
        val w = bmp.width
        val h = bmp.height
        val rw = r - l
        val rh = b - t
        if (rw < 6 || rh < 6) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // رنگ‌های مرجع: میانگین ۳×۳ پیکسل در هر چهار گوشه‌ی عکس
        val refs = IntArray(4)
        var refCount = 0
        for (k in 0 until 4) {
            val x0 = if (k % 2 == 0) l else r - 3
            val y0 = if (k < 2) t else b - 3
            var sr = 0
            var sg = 0
            var sb = 0
            var n = 0
            for (dy in 0 until 3) {
                for (dx in 0 until 3) {
                    val c = px[(y0 + dy) * w + (x0 + dx)]
                    if ((c ushr 24) >= 200) {
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                        n++
                    }
                }
            }
            if (n > 0) {
                refs[refCount] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                refCount++
            }
        }

        val removed = BooleanArray(w * h)
        val queue = IntArray(rw * rh)
        var head = 0
        var tail = 0

        // شروع از لبه‌های عکس
        for (x in l until r) {
            val top = t * w + x
            val bottom = (b - 1) * w + x
            if (!removed[top] && isBackground(px[top], refs, refCount, tol)) {
                removed[top] = true
                queue[tail++] = top
            }
            if (!removed[bottom] && isBackground(px[bottom], refs, refCount, tol)) {
                removed[bottom] = true
                queue[tail++] = bottom
            }
        }
        for (y in t until b) {
            val left = y * w + l
            val right = y * w + (r - 1)
            if (!removed[left] && isBackground(px[left], refs, refCount, tol)) {
                removed[left] = true
                queue[tail++] = left
            }
            if (!removed[right] && isBackground(px[right], refs, refCount, tol)) {
                removed[right] = true
                queue[tail++] = right
            }
        }

        // گسترش به پیکسل‌های هم‌رنگ و به‌هم‌چسبیده
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > l) {
                val n = i - 1
                if (!removed[n] && isBackground(px[n], refs, refCount, tol)) {
                    removed[n] = true
                    queue[tail++] = n
                }
            }
            if (x < r - 1) {
                val n = i + 1
                if (!removed[n] && isBackground(px[n], refs, refCount, tol)) {
                    removed[n] = true
                    queue[tail++] = n
                }
            }
            if (y > t) {
                val n = i - w
                if (!removed[n] && isBackground(px[n], refs, refCount, tol)) {
                    removed[n] = true
                    queue[tail++] = n
                }
            }
            if (y < b - 1) {
                val n = i + w
                if (!removed[n] && isBackground(px[n], refs, refCount, tol)) {
                    removed[n] = true
                    queue[tail++] = n
                }
            }
        }

        // اگر تقریباً همه‌ی عکس پس‌زمینه تشخیص داده شد، دست نمی‌زنیم (حساسیت خیلی زیاد است)
        val total = rw * rh
        if (tail == 0 || tail.toDouble() > total * 0.97) return

        // لبه‌ی نرم: پیکسل‌های سوژه که کنار پس‌زمینه‌ی حذف‌شده‌اند کمی شفاف می‌شوند
        for (y in t until b) {
            for (x in l until r) {
                val i = y * w + x
                if (removed[i]) continue
                val touching = (x > l && removed[i - 1]) ||
                    (x < r - 1 && removed[i + 1]) ||
                    (y > t && removed[i - w]) ||
                    (y < b - 1 && removed[i + w])
                if (touching) px[i] = (150 shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        for (i in 0 until tail) {
            px[queue[i]] = 0
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** یک حاشیه‌ی سفید دور قسمت‌های دیدنی استیکر می‌کشد (تا روی هر زمینه‌ای خوانا باشد). */
    private fun addOutline(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val inside = BooleanArray(w * h)
        var any = false
        for (i in px.indices) {
            if ((px[i] ushr 24) > 24) {
                inside[i] = true
                any = true
            }
        }
        if (!any) return
        val grown = dilate(inside, w, h, radius)
        var changed = false
        for (i in px.indices) {
            if (grown[i] && !inside[i]) {
                px[i] = Color.WHITE
                changed = true
            }
        }
        if (changed) bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** بزرگ‌کردنِ ناحیه‌ی true به اندازه‌ی radius پیکسل (افقی و بعد عمودی). */
    private fun dilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        val prefix = IntArray(maxOf(w, h) + 1)
        for (y in 0 until h) {
            val row = y * w
            prefix[0] = 0
            for (x in 0 until w) {
                prefix[x + 1] = prefix[x] + (if (src[row + x]) 1 else 0)
            }
            for (x in 0 until w) {
                val a = maxOf(0, x - radius)
                val b = minOf(w, x + radius + 1)
                tmp[row + x] = prefix[b] - prefix[a] > 0
            }
        }
        for (x in 0 until w) {
            prefix[0] = 0
            for (y in 0 until h) {
                prefix[y + 1] = prefix[y] + (if (tmp[y * w + x]) 1 else 0)
            }
            for (y in 0 until h) {
                val a = maxOf(0, y - radius)
                val b = minOf(h, y + radius + 1)
                out[y * w + x] = prefix[b] - prefix[a] > 0
            }
        }
        return out
    }
}

class PhotoStickerActivity : PhotoBatchActivity<StickerOptions>() {

    private lateinit var sizeChips: PhotoChips
    private lateinit var fitBox: LinearLayout
    private lateinit var fitChips: PhotoChips
    private lateinit var bgChips: PhotoChips
    private lateinit var toleranceBox: LinearLayout
    private lateinit var toleranceLabel: TextView
    private lateinit var outlineChips: PhotoChips
    private lateinit var previewCard: LinearLayout
    private lateinit var previewImage: ImageView
    private var tolerance = 30
    private var previewBase: Bitmap? = null
    private var baseToken = 0
    private var renderToken = 0

    override fun toolTitle(): String = "ساخت استیکر"

    override fun actionLabel(): String = "ساخت استیکرها"

    override fun resultHint(): String =
        "برای ارسال، دکمه‌ی «اشتراک‌گذاری» را بزنید و ایتا را انتخاب کنید (نه از سنجاق داخل ایتا)."

    private fun updateToleranceLabel() {
        toleranceLabel.text = "حساسیت حذف پس‌زمینه: " + tolerance
    }

    private fun updateVisibility() {
        fitBox.visibility = if (sizeChips.selected == 0) View.VISIBLE else View.GONE
        toleranceBox.visibility = if (bgChips.selected == 1) View.VISIBLE else View.GONE
    }

    private fun onOptionChanged() {
        updateVisibility()
        refreshPreview()
    }

    private fun checkerDrawable(): BitmapDrawable {
        val cell = dp(10)
        val size = cell * 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.parseColor("#E5E7EB")
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = Color.parseColor("#F9FAFB")
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), size.toFloat(), size.toFloat(), p)
        val d = BitmapDrawable(resources, bmp)
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return d
    }

    override fun buildOptions(container: LinearLayout) {
        val card = newCard(container, "تنظیمات استیکر")

        addLabel(card, "اندازه‌ی استیکر", 0)
        sizeChips = addChips(card, listOf("مربع ۵۱۲×۵۱۲ (پیشنهادی)", "نسبت اصلی عکس (ضلع بلند ۵۱۲)"), 0) { onOptionChanged() }

        fitBox = LinearLayout(this)
        fitBox.orientation = LinearLayout.VERTICAL
        addLabel(fitBox, "جای عکس در کادر", 14)
        fitChips = addChips(fitBox, listOf("کل عکس دیده شود", "پر کردن کادر (برش از وسط)"), 0) { onOptionChanged() }
        card.addView(fitBox, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))

        addLabel(card, "پس‌زمینه", 14)
        bgChips = addChips(card, listOf("نگه داشتن پس‌زمینه", "حذف پس‌زمینه‌ی ساده"), 0) { onOptionChanged() }

        toleranceBox = LinearLayout(this)
        toleranceBox.orientation = LinearLayout.VERTICAL
        toleranceLabel = PhotoUi.text(this, "", 14f, PhotoUi.color(this, R.color.text_main), true)
        toleranceBox.addView(toleranceLabel, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        toleranceBox.addView(
            PhotoUi.seekBar(this, 5, 80, tolerance) { v ->
                tolerance = v
                updateToleranceLabel()
                refreshPreview()
            },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        addNote(
            toleranceBox,
            "فقط برای پس‌زمینه‌ی ساده و یکدست خوب کار می‌کند. اگر بخشی از خودِ سوژه پاک شد، عدد را کم کنید؛ اگر پس‌زمینه ماند، زیاد کنید.",
            4
        )
        val tbLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        tbLp.topMargin = dp(10)
        card.addView(toleranceBox, tbLp)
        updateToleranceLabel()

        addLabel(card, "حاشیه", 14)
        outlineChips = addChips(card, listOf("بدون حاشیه", "حاشیه‌ی سفید دور استیکر"), 0) { onOptionChanged() }

        addNote(
            card,
            "خروجی WebP است؛ یک ضلعش دقیقاً ۵۱۲ پیکسل و حجمش کمتر از ۵۱۲ کیلوبایت. جاهای خالی شفاف می‌ماند.",
            12
        )

        previewCard = newCard(container, "پیش‌نمایش (اولین عکس)")
        previewImage = ImageView(this)
        previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        previewImage.background = checkerDrawable()
        previewCard.addView(previewImage, LinearLayout.LayoutParams(PhotoUi.MATCH, dp(300)))
        previewCard.visibility = View.GONE

        updateVisibility()
    }

    private fun currentOptions(): StickerOptions {
        return StickerOptions(
            sizeChips.selected == 0,
            fitChips.selected == 1,
            bgChips.selected == 1,
            tolerance,
            outlineChips.selected == 1
        )
    }

    override fun onSourcesChanged() {
        val first = if (sources.isEmpty()) null else sources[0]
        baseToken++
        val token = baseToken
        if (first == null) {
            previewBase = null
            previewCard.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoStickerActivity, first.uri, 700) }
            if (token == baseToken) {
                previewBase = bmp
                refreshPreview()
            }
        }
    }

    private fun refreshPreview() {
        val base = previewBase
        if (base == null) {
            previewCard.visibility = View.GONE
            return
        }
        previewCard.visibility = View.VISIBLE
        val options = currentOptions()
        renderToken++
        val token = renderToken
        lifecycleScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                try {
                    PhotoSticker.render(base, options)
                } catch (t: Throwable) {
                    null
                }
            }
            if (rendered != null && token == renderToken) {
                previewImage.setImageBitmap(rendered)
            }
        }
    }

    override fun readOptions(): StickerOptions? {
        return currentOptions()
    }

    override fun processOne(source: PhotoSource, options: StickerOptions, index: Int): PhotoResult? {
        val decoded = PhotoImageUtils.decode(this, source.uri, 1100) ?: return null
        val sticker = PhotoSticker.render(decoded, options)
        decoded.recycle()
        val enc = PhotoSticker.encodeWithinSize(sticker, PhotoSticker.MAX_BYTES)
        val w = sticker.width
        val h = sticker.height
        sticker.recycle()
        val file = PhotoFiles.newFile(this, "Sticker_" + PhotoImageUtils.baseName(source.name) + ".webp")
        file.writeBytes(enc.bytes)
        val note = if (enc.reachedTarget) "" else "حجم این استیکر از ۵۱۲ کیلوبایت بیشتر شد."
        return PhotoResult(file, file.name, "image/webp", source.name, source.sizeBytes, w, h, note)
    }
}
