package com.kafinet.asannet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.Locale

/* ===================================================================
 *  «عکس پرسنلی» و «ویرایش عکس»
 *  - عکس (کادر) بالای صفحه ثابت می‌ماند و فقط تنظیمات پایین اسکرول می‌شود.
 *  - نور، رنگ، چرخش، آینه و زاویه‌ی دقیق به‌صورت زنده روی عکس دیده می‌شود.
 *  - خروجی همیشه از روی عکس اصلی با کیفیت کامل ساخته می‌شود (نه از روی پیش‌نمایش).
 * =================================================================== */

/* ------------------------------------------------------------------
 *  عکس پرسنلی (۳×۴، ۴×۶ و ...) با ویرایش کامل و برگه‌ی چاپ
 * ------------------------------------------------------------------ */

class PhotoIdActivity : PhotoToolActivity() {

    private lateinit var frameView: PhotoFrameView
    private lateinit var sizeChips: PhotoChips
    private lateinit var customBox: LinearLayout
    private lateinit var customW: EditText
    private lateinit var customH: EditText
    private lateinit var fillChips: PhotoChips
    private lateinit var qualityChips: PhotoChips
    private lateinit var sheetChips: PhotoChips
    private lateinit var copiesBox: LinearLayout
    private lateinit var copiesChips: PhotoChips
    private lateinit var capacityText: TextView
    private lateinit var adjust: PhotoAdjustPanel
    private lateinit var rotate: PhotoRotatePanel
    private lateinit var panel: PhotoResultPanel

    private var sourceUri: Uri? = null
    private var baseBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private val ops = ArrayList<EditOp>()
    private var angle = 0f
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

    private fun cmToPx(cm: Double, dpi: Int): Int = Math.round(cm / 2.54 * dpi).toInt()

    private fun mmToPx(mm: Double, dpi: Int): Int = Math.round(mm / 25.4 * dpi).toInt()

    private fun formatCm(v: Double): String {
        return if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
    }

    /** تراکم چاپِ برگه‌ها: ۱۰×۱۵ در حالت «بالا» ۶۰۰ و بقیه ۳۰۰. */
    private fun sheetDpi(sheetMode: Int, quality: Int): Int = if (sheetMode == 1 && quality == 1) 600 else 300

    private fun sheetWidthPx(sheetMode: Int, dpi: Int): Int = if (sheetMode == 1) cmToPx(10.0, dpi) else cmToPx(21.0, dpi)

    private fun sheetHeightPx(sheetMode: Int, dpi: Int): Int = if (sheetMode == 1) cmToPx(15.0, dpi) else cmToPx(29.7, dpi)

    /** اندازه‌ی پیکسلیِ عکس تکی بر اساس کیفیت (۰: ۳۰۰ dpi، ۱: ۶۰۰ dpi، ۲: ضلع بلند ۱۹۲۰ پیکسل). */
    private fun singlePx(size: DoubleArray, quality: Int): IntArray {
        if (quality == 2) {
            val longCm = Math.max(size[0], size[1])
            val k = 1920.0 / longCm
            return intArrayOf(
                Math.max(1, Math.round(size[0] * k).toInt()),
                Math.max(1, Math.round(size[1] * k).toInt())
            )
        }
        val dpi = if (quality == 1) 600 else 300
        return intArrayOf(cmToPx(size[0], dpi), cmToPx(size[1], dpi))
    }

    private fun singleDpi(size: DoubleArray, px: IntArray): Int {
        val longCm = Math.max(size[0], size[1])
        val longPx = Math.max(px[0], px[1])
        return Math.round(longPx / (longCm / 2.54)).toInt().coerceIn(72, 65000)
    }

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
        val dpi = sheetDpi(mode, qualityChips.selected)
        val cap = PhotoImageUtils.sheetCapacity(
            sheetWidthPx(mode, dpi),
            sheetHeightPx(mode, dpi),
            cmToPx(size[0], dpi),
            cmToPx(size[1], dpi),
            mmToPx(4.0, dpi),
            mmToPx(2.0, dpi)
        )
        capacityText.text = "روی این برگه تا " + cap + " عکس جا می‌شود (برگه با کیفیت " + dpi + " dpi ساخته می‌شود)."
        capacityText.visibility = View.VISIBLE
    }

    override fun buildContent(container: LinearLayout) {
        // بخش ثابت بالای صفحه: کادر عکس
        frameView = PhotoFrameView(this)
        pinned.addView(frameView, LinearLayout.LayoutParams(PhotoUi.MATCH, pinnedHeight(0.4f, 220, 420)))
        pinned.visibility = View.VISIBLE

        val card1 = newCard(container, "۱) عکس")
        card1.addView(
            PhotoUi.button(this, "انتخاب عکس از گالری", false) { pickPhoto.launch("image/*") },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        addNote(
            card1,
            "با یک انگشت عکس را جابه‌جا و با دو انگشت بزرگ و کوچک کنید تا صورت وسط بیضی قرار بگیرد. هر چه داخل کادر باشد در خروجی می‌آید. عکس بالای صفحه ثابت می‌ماند و فقط تنظیمات پایین اسکرول می‌شود.",
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

        addLabel(card2, "کیفیت خروجی", 14)
        qualityChips = addChips(
            card2,
            listOf("استاندارد چاپ (۳۰۰ dpi)", "بالا (۶۰۰ dpi)", "فول اچ‌دی (ضلع بلند ۱۹۲۰ پیکسل)"),
            2
        ) { updateCapacity() }
        addNote(
            card2,
            "برای چاپ، «بالا» یا «فول اچ‌دی» بهترین کیفیت را می‌دهند. برگه‌ی A4 همیشه ۳۰۰ dpi است و برگه‌ی ۱۰×۱۵ فقط در حالت «بالا» ۶۰۰ dpi می‌شود.",
            6
        )

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

        val card3 = newCard(container, "۳) چرخش و آینه")
        rotate = PhotoRotatePanel(this, card3, { which -> onRotateAction(which) }, { deg -> onAngleChanged(deg) })

        val card4 = newCard(container, "۴) نور و رنگ")
        adjust = PhotoAdjustPanel(this, card4, { currentBitmap }, { applyFilter() })

        val build = PhotoUi.button(this, "ساخت عکس", true) { buildPhoto() }
        val buildLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        buildLp.bottomMargin = dp(12)
        container.addView(build, buildLp)

        panel = PhotoResultPanel(this, container)

        applySizeChoice()
        applySheetChoice()
    }

    private fun applyFilter() {
        if (!::frameView.isInitialized || !::adjust.isInitialized) return
        frameView.setPreviewFilter(adjust.filterOrNull())
    }

    private fun onRotateAction(which: Int) {
        if (baseBitmap == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        when (which) {
            0 -> ops.add(EditOp(EditOp.ROT_RIGHT, null))
            1 -> ops.add(EditOp(EditOp.ROT_LEFT, null))
            2 -> ops.add(EditOp(EditOp.FLIP_H, null))
            else -> ops.add(EditOp(EditOp.FLIP_V, null))
        }
        recompute()
    }

    private fun onAngleChanged(deg: Float) {
        angle = deg
        if (baseBitmap != null) recompute()
    }

    /** عکسِ نمایشی را از روی عکس پایه و مرحله‌های ویرایش دوباره می‌سازد. */
    private fun recompute() {
        val base = baseBitmap ?: return
        val next = try {
            PhotoEditPipeline.apply(base, angle, ops)
        } catch (t: Throwable) {
            null
        }
        if (next == null) {
            toast("حافظه‌ی گوشی برای این کار کافی نیست")
            return
        }
        val old = currentBitmap
        currentBitmap = next
        frameView.setBitmap(next)
        applyFilter()
        if (old != null && old !== base && old !== next) old.recycle()
    }

    private fun loadPhoto(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoIdActivity, uri, 2600) }
            if (bmp == null) {
                toast("این عکس باز نشد")
                return@launch
            }
            sourceUri = uri
            baseBitmap = bmp
            ops.clear()
            angle = 0f
            rotate.reset()
            adjust.reset()
            recompute()
            panel.hide()
        }
    }

    private fun buildPhoto() {
        if (building) return
        val uri = sourceUri
        if (uri == null || !frameView.hasBitmap()) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        val size = currentSizeCm(true) ?: return
        val rect = frameView.frameRectInBitmap()
        if (rect == null) {
            toast("ساخت عکس ممکن نشد")
            return
        }
        val sheetMode = sheetChips.selected
        val quality = qualityChips.selected
        val copies = copiesValues[copiesChips.selected]
        val fill = fillColors[fillChips.selected]
        val cm = adjust.colorMatrixOrNull()
        val opsCopy = ArrayList<EditOp>(ops)
        val angleCopy = angle
        building = true
        toast("در حال ساخت عکس با کیفیت بالا...")
        lifecycleScope.launch {
            val made = withContext(Dispatchers.Default) {
                try {
                    makeOutput(uri, angleCopy, opsCopy, rect, fill, cm, size, sheetMode, quality, copies)
                } catch (t: Throwable) {
                    null
                }
            }
            building = false
            if (made == null) {
                toast("ساخت خروجی ممکن نشد؛ اندازه‌ی عکس برای این برگه مناسب نیست یا حافظه‌ی گوشی کم است")
                return@launch
            }
            panel.show(made.first, made.second)
        }
    }

    private fun makeOutput(
        uri: Uri,
        angleValue: Float,
        opsList: List<EditOp>,
        rect: RectF,
        fill: Int,
        cm: ColorMatrix?,
        size: DoubleArray,
        sheetMode: Int,
        quality: Int,
        copies: Int
    ): Pair<PhotoResult, String>? {
        val full = PhotoImageUtils.decode(this, uri, 3600) ?: return null
        val edited = PhotoEditPipeline.apply(full, angleValue, opsList)
        if (edited !== full) full.recycle()

        val sheetDpiValue = sheetDpi(sheetMode, quality)
        val px: IntArray = if (sheetMode == 0) {
            singlePx(size, quality)
        } else {
            intArrayOf(cmToPx(size[0], sheetDpiValue), cmToPx(size[1], sheetDpiValue))
        }
        val photo = PhotoEditPipeline.renderRegion(edited, rect, px[0], px[1], fill, cm)
        edited.recycle()

        val sizeLabel = formatCm(size[0]) + "x" + formatCm(size[1])
        val sizeText = formatCm(size[0]) + " در " + formatCm(size[1]) + " سانتی‌متر"
        val name: String
        val info: String
        val bytes: ByteArray
        if (sheetMode == 0) {
            val dpi = singleDpi(size, px)
            bytes = PhotoImageUtils.setJpegDpi(PhotoImageUtils.encodeBytes(photo, PhotoImageUtils.Fmt.JPEG, 98), dpi)
            name = "ID_" + sizeLabel + ".jpg"
            info = "اندازه‌ی چاپ: " + sizeText + " • " + px[0] + " در " + px[1] + " پیکسل (حدود " + dpi + " dpi) • حجم: " +
                PhotoImageUtils.formatSize(bytes.size.toLong())
        } else {
            val sheetW = sheetWidthPx(sheetMode, sheetDpiValue)
            val sheetH = sheetHeightPx(sheetMode, sheetDpiValue)
            val margin = mmToPx(4.0, sheetDpiValue)
            val gap = mmToPx(2.0, sheetDpiValue)
            val capacity = PhotoImageUtils.sheetCapacity(sheetW, sheetH, px[0], px[1], margin, gap)
            val sheet = PhotoImageUtils.composeSheet(photo, sheetW, sheetH, margin, gap, copies)
            if (sheet == null) {
                photo.recycle()
                return null
            }
            bytes = PhotoImageUtils.setJpegDpi(PhotoImageUtils.encodeBytes(sheet, PhotoImageUtils.Fmt.JPEG, 98), sheetDpiValue)
            sheet.recycle()
            val count = if (copies > 0) minOf(copies, capacity) else capacity
            val sheetName = if (sheetMode == 1) "10x15" else "A4"
            val sheetText = if (sheetMode == 1) "۱۰×۱۵ سانتی‌متر" else "A4"
            name = "ID_" + sizeLabel + "_sheet_" + sheetName + ".jpg"
            info = "برگه‌ی " + sheetText + " با " + count + " عکس " + sizeText + " • " + sheetDpiValue + " dpi • حجم: " +
                PhotoImageUtils.formatSize(bytes.size.toLong())
        }
        val outW = px[0]
        val outH = px[1]
        photo.recycle()
        val file = PhotoFiles.newFile(this, name)
        file.writeBytes(bytes)
        val result = PhotoResult(file, file.name, "image/jpeg", "", 0L, outW, outH)
        return Pair(result, info)
    }
}

/* ------------------------------------------------------------------
 *  ویرایش عکس: برش (با بزرگ‌نمایی بعد از اعمال)، چرخش، آینه، زاویه‌ی دقیق، نور و رنگ
 * ------------------------------------------------------------------ */

class PhotoEditorActivity : PhotoToolActivity() {

    private lateinit var cropView: PhotoCropView
    private lateinit var ratioChips: PhotoChips
    private lateinit var formatChips: PhotoChips
    private lateinit var qualityBox: LinearLayout
    private lateinit var qualityLabel: TextView
    private lateinit var adjust: PhotoAdjustPanel
    private lateinit var rotate: PhotoRotatePanel
    private lateinit var panel: PhotoResultPanel

    private var sourceUri: Uri? = null
    private var sourceName = "photo"
    private var sourceBytes = -1L
    private var baseBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private val ops = ArrayList<EditOp>()
    private var angle = 0f
    private var quality = 92
    private var working = false

    private val ratios = floatArrayOf(0f, 1f, 3f / 4f, 4f / 3f, 9f / 16f, 16f / 9f, 2f / 3f, 3f / 2f)
    private val formats = listOf(PhotoImageUtils.Fmt.JPEG, PhotoImageUtils.Fmt.PNG, PhotoImageUtils.Fmt.WEBP)

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun toolTitle(): String = "ویرایش عکس"

    override fun buildContent(container: LinearLayout) {
        // بخش ثابت بالای صفحه: عکس + دکمه‌های برش
        cropView = PhotoCropView(this)
        pinned.addView(cropView, LinearLayout.LayoutParams(PhotoUi.MATCH, pinnedHeight(0.36f, 200, 380)))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val applyButton = PhotoUi.button(this, "اعمال برش", true) { applyCrop() }
        val undoButton = PhotoUi.button(this, "برگرداندن", false) { undoLast() }
        val resetButton = PhotoUi.button(this, "بازنشانی", false) { resetAll() }
        val barButtons = listOf(applyButton, undoButton, resetButton)
        for (i in barButtons.indices) {
            val b = barButtons[i]
            b.setPadding(dp(4), dp(9), dp(4), dp(9))
            val lp = LinearLayout.LayoutParams(0, PhotoUi.WRAP, 1f)
            if (i < barButtons.size - 1) lp.marginEnd = dp(6)
            bar.addView(b, lp)
        }
        val barLp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        barLp.topMargin = dp(8)
        pinned.addView(bar, barLp)
        pinned.visibility = View.VISIBLE

        val card1 = newCard(container, "۱) عکس و برش")
        card1.addView(
            PhotoUi.button(this, "انتخاب عکس از گالری", false) { pickPhoto.launch("image/*") },
            LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        )
        addNote(
            card1,
            "کادر سفید را بکشید یا از گوشه‌ها بزرگ و کوچکش کنید، بعد «اعمال برش» را بزنید؛ عکسِ برش‌خورده بزرگ می‌شود تا نتیجه را ببینید. می‌توانید دوباره برش بزنید یا با «برگرداندن» یک مرحله عقب بروید.",
            8
        )
        addLabel(card1, "نسبت کادر برش", 10)
        ratioChips = addChips(
            card1,
            listOf("آزاد", "۱:۱", "۳:۴", "۴:۳", "۹:۱۶", "۱۶:۹", "۲:۳", "۳:۲"),
            0
        ) { index -> cropView.setRatio(ratios[index]) }

        val card2 = newCard(container, "۲) چرخش و آینه")
        rotate = PhotoRotatePanel(this, card2, { which -> onRotateAction(which) }, { deg -> onAngleChanged(deg) })

        val card3 = newCard(container, "۳) نور و رنگ")
        adjust = PhotoAdjustPanel(this, card3, { currentBitmap }, { applyFilter() })

        val card4 = newCard(container, "۴) خروجی")
        addLabel(card4, "فرمت", 0)
        formatChips = addChips(card4, listOf("JPG", "PNG", "WebP"), 0) { updateQualityVisibility() }
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
        card4.addView(qualityBox, LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP))
        updateQualityLabel()
        addNote(card4, "خروجی از روی عکس اصلی و با اندازه‌ی کامل ساخته می‌شود، نه از روی پیش‌نمایش.", 8)

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

    private fun applyFilter() {
        if (!::cropView.isInitialized || !::adjust.isInitialized) return
        cropView.setPreviewFilter(adjust.filterOrNull())
    }

    private fun loadPhoto(uri: Uri) {
        val info = PhotoImageUtils.queryInfo(this, uri)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PhotoImageUtils.decode(this@PhotoEditorActivity, uri, 1800) }
            if (bmp == null) {
                toast("این عکس باز نشد")
                return@launch
            }
            sourceUri = uri
            sourceName = info.first
            sourceBytes = info.second
            baseBitmap = bmp
            ops.clear()
            angle = 0f
            rotate.reset()
            adjust.reset()
            recompute()
            panel.hide()
        }
    }

    /** عکسِ نمایشی را از روی عکس پایه و مرحله‌های ویرایش دوباره می‌سازد و در بخش ثابت نشان می‌دهد. */
    private fun recompute() {
        val base = baseBitmap ?: return
        val next = try {
            PhotoEditPipeline.apply(base, angle, ops)
        } catch (t: Throwable) {
            null
        }
        if (next == null) {
            toast("حافظه‌ی گوشی برای این کار کافی نیست")
            return
        }
        val old = currentBitmap
        currentBitmap = next
        cropView.setBitmap(next)
        applyFilter()
        if (old != null && old !== base && old !== next) old.recycle()
    }

    private fun onRotateAction(which: Int) {
        if (baseBitmap == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        when (which) {
            0 -> ops.add(EditOp(EditOp.ROT_RIGHT, null))
            1 -> ops.add(EditOp(EditOp.ROT_LEFT, null))
            2 -> ops.add(EditOp(EditOp.FLIP_H, null))
            else -> ops.add(EditOp(EditOp.FLIP_V, null))
        }
        recompute()
    }

    private fun onAngleChanged(deg: Float) {
        angle = deg
        if (baseBitmap != null) recompute()
    }

    /** برش را اعمال می‌کند: قسمت‌های بیرونِ کادر کنار می‌روند و بقیه‌ی عکس بزرگ می‌شود. */
    private fun applyCrop() {
        if (baseBitmap == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        val r = cropView.normalizedCrop()
        if (r.right - r.left > 0.985f && r.bottom - r.top > 0.985f) {
            toast("اول کادر سفید را کوچک‌تر کنید، بعد «اعمال برش» را بزنید")
            return
        }
        ops.add(EditOp(EditOp.CROP, RectF(r)))
        recompute()
    }

    private fun undoLast() {
        if (ops.isEmpty()) {
            toast("مرحله‌ای برای برگرداندن نیست")
            return
        }
        ops.removeAt(ops.size - 1)
        recompute()
    }

    private fun resetAll() {
        if (baseBitmap == null) return
        ops.clear()
        angle = 0f
        rotate.reset()
        recompute()
    }

    private fun exportResult() {
        if (working) return
        val uri = sourceUri
        if (uri == null) {
            toast("ابتدا یک عکس انتخاب کنید")
            return
        }
        // اگر کادر برشِ روی صفحه هنوز اعمال نشده، آن هم در خروجی می‌آید (همان چیزی که داخل کادر سفید است)
        val pending = cropView.normalizedCrop()
        val opsCopy = ArrayList<EditOp>(ops)
        if (pending.right - pending.left < 0.985f || pending.bottom - pending.top < 0.985f) {
            opsCopy.add(EditOp(EditOp.CROP, RectF(pending)))
        }
        val angleCopy = angle
        val cm = adjust.colorMatrixOrNull()
        val format = formats[formatChips.selected]
        val q = quality
        val name = sourceName
        val originalBytes = sourceBytes
        working = true
        toast("در حال ساخت عکس با کیفیت کامل...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    renderFinal(uri, angleCopy, opsCopy, cm, format, q, name, originalBytes)
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
        angleValue: Float,
        opsList: List<EditOp>,
        cm: ColorMatrix?,
        format: PhotoImageUtils.Fmt,
        q: Int,
        name: String,
        originalBytes: Long
    ): PhotoResult? {
        val full = PhotoImageUtils.decode(this, uri, 3600) ?: return null
        val edited = PhotoEditPipeline.apply(full, angleValue, opsList)
        if (edited !== full) full.recycle()

        var finalBitmap = edited
        if (cm != null) {
            val colored = Bitmap.createBitmap(edited.width, edited.height, Bitmap.Config.ARGB_8888)
            if (!edited.hasAlpha()) colored.setHasAlpha(false)
            val canvas = Canvas(colored)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(edited, 0f, 0f, paint)
            edited.recycle()
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
