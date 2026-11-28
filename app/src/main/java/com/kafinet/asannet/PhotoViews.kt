package com.kafinet.asannet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup

/**
 * کادر ثابت با نسبت دلخواه (مثل ۳×۴)؛ کاربر عکس را زیر کادر جابه‌جا و با دو انگشت بزرگ/کوچک می‌کند.
 * هر چیزی که داخل کادر باشد، همان در خروجی می‌آید. اگر عکس کوچک‌تر از کادر شود، جای خالی با رنگ زمینه پر می‌شود.
 */
class PhotoFrameView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var ratio = 0.75f
    private val frame = RectF()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var minScale = 0.1f
    private var maxScale = 10f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val density = context.resources.displayMetrics.density

    var fillColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val dimPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    init {
        dimPaint.color = Color.argb(150, 0, 0, 0)
        borderPaint.color = Color.WHITE
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 2f * density
        guidePaint.color = Color.argb(140, 255, 255, 255)
        guidePaint.style = Paint.Style.STROKE
        guidePaint.strokeWidth = 1.5f * density
        guidePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
        textPaint.color = Color.argb(220, 255, 255, 255)
        textPaint.textSize = 18f * density
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = PhotoUi.font(context)
        setBackgroundColor(Color.parseColor("#1F1B2E"))
    }

    fun hasBitmap(): Boolean = bitmap != null

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        resetTransform()
        invalidate()
    }

    /** فیلتر رنگ برای پیش‌نمایشِ زنده‌ی نور و رنگ. */
    fun setPreviewFilter(filter: ColorFilter?) {
        bitmapPaint.colorFilter = filter
        invalidate()
    }

    /**
     * محدوده‌ی کادر را به‌صورت درصدی از عکس برمی‌گرداند (چپ، بالا، راست، پایین).
     * اگر عکس کوچک‌تر از کادر باشد، مقدارها می‌توانند بیرون از ۰ تا ۱ باشند.
     */
    fun frameRectInBitmap(): RectF? {
        val bmp = bitmap ?: return null
        if (frame.width() <= 0f || scale <= 0f) return null
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        return RectF(
            (frame.left - tx) / scale / bw,
            (frame.top - ty) / scale / bh,
            (frame.right - tx) / scale / bw,
            (frame.bottom - ty) / scale / bh
        )
    }

    /** نسبت کادر (عرض به ارتفاع)؛ مثلاً ۳ و ۴ برای عکس ۳×۴. */
    fun setFrameRatio(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        ratio = w / h
        recomputeFrame()
        resetTransform()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFrame()
        resetTransform()
    }

    private fun recomputeFrame() {
        val pad = 24f * density
        val availW = width - 2f * pad
        val availH = height - 2f * pad
        if (availW <= 0f || availH <= 0f) return
        var fw = availW
        var fh = fw / ratio
        if (fh > availH) {
            fh = availH
            fw = fh * ratio
        }
        val left = (width - fw) / 2f
        val top = (height - fh) / 2f
        frame.set(left, top, left + fw, top + fh)
    }

    private fun resetTransform() {
        val bmp = bitmap ?: return
        if (frame.width() <= 0f || frame.height() <= 0f) return
        val cover = maxOf(frame.width() / bmp.width, frame.height() / bmp.height)
        scale = cover
        minScale = cover * 0.5f
        maxScale = cover * 8f
        tx = frame.centerX() - bmp.width * scale / 2f
        ty = frame.centerY() - bmp.height * scale / 2f
    }

    private fun clampTranslation() {
        val bmp = bitmap ?: return
        val iw = bmp.width * scale
        val ih = bmp.height * scale
        tx = if (iw >= frame.width()) {
            clampF(tx, frame.right - iw, frame.left)
        } else {
            clampF(tx, frame.left, frame.right - iw)
        }
        ty = if (ih >= frame.height()) {
            clampF(ty, frame.bottom - ih, frame.top)
        } else {
            clampF(ty, frame.top, frame.bottom - ih)
        }
    }

    private fun clampF(v: Float, lo: Float, hi: Float): Float {
        if (hi < lo) return lo
        if (v < lo) return lo
        if (v > hi) return hi
        return v
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (bitmap == null) return
        val newScale = clampF(scale * factor, minScale, maxScale)
        val k = newScale / scale
        tx = focusX - (focusX - tx) * k
        ty = focusY - (focusY - ty) * k
        scale = newScale
        clampTranslation()
        invalidate()
    }

    /** آنچه داخل کادر دیده می‌شود را با اندازه‌ی دقیق پیکسلیِ خروجی می‌سازد. */
    fun renderCrop(outW: Int, outH: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (frame.width() <= 0f || frame.height() <= 0f || outW <= 0 || outH <= 0) return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(false)
        val canvas = Canvas(out)
        canvas.drawColor(fillColor or Color.BLACK)
        val kx = outW / frame.width()
        val ky = outH / frame.height()
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(tx - frame.left, ty - frame.top)
        m.postScale(kx, ky)
        canvas.drawBitmap(bmp, m, bitmapPaint)
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("ابتدا یک عکس انتخاب کنید", width / 2f, height / 2f, textPaint)
            return
        }
        fillPaint.color = fillColor
        canvas.drawRect(frame, fillPaint)

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, w, h, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint)
        canvas.drawRect(frame, borderPaint)

        // راهنمای جای صورت
        val ovalW = frame.width() * 0.56f
        val ovalH = frame.height() * 0.64f
        val cx = frame.centerX()
        val top = frame.top + frame.height() * 0.12f
        canvas.drawOval(cx - ovalW / 2f, top, cx + ovalW / 2f, top + ovalH, guidePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    dragging = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    tx += event.x - lastX
                    ty += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clampTranslation()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

/**
 * عکس کامل نمایش داده می‌شود و روی آن یک کادر برش هست که کاربر می‌تواند جابه‌جا کند یا از گوشه‌ها
 * (و در حالت «آزاد»، از لبه‌ها) بزرگ و کوچکش کند. مختصات برش به‌صورت درصد (۰ تا ۱) داده می‌شود
 * تا روی عکس اصلی با کیفیت کامل هم اعمال شود.
 */
class PhotoCropView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val crop = RectF()
    private var ratio = 0f
    private val density = context.resources.displayMetrics.density
    private var dragMode = DRAG_NONE
    private var lastX = 0f
    private var lastY = 0f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_MOVE = 1
        private const val DRAG_TL = 2
        private const val DRAG_TR = 3
        private const val DRAG_BL = 4
        private const val DRAG_BR = 5
        private const val DRAG_L = 6
        private const val DRAG_T = 7
        private const val DRAG_R = 8
        private const val DRAG_B = 9
    }

    init {
        dimPaint.color = Color.argb(150, 0, 0, 0)
        borderPaint.color = Color.WHITE
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 1.5f * density
        gridPaint.color = Color.argb(110, 255, 255, 255)
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f * density
        handlePaint.color = Color.WHITE
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 4f * density
        handlePaint.strokeCap = Paint.Cap.ROUND
        hintPaint.color = Color.argb(220, 255, 255, 255)
        hintPaint.textSize = 18f * density
        hintPaint.textAlign = Paint.Align.CENTER
        hintPaint.typeface = PhotoUi.font(context)
        setBackgroundColor(Color.parseColor("#1F1B2E"))
    }

    fun hasBitmap(): Boolean = bitmap != null

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        layoutImage()
        resetCrop()
        invalidate()
    }

    /** نسبت کادر برش (عرض به ارتفاع)؛ صفر یعنی آزاد. */
    fun setRatio(r: Float) {
        ratio = r
        if (bitmap != null) {
            applyRatio()
            invalidate()
        }
    }

    /** فیلتر رنگ فقط برای پیش‌نمایش (نور، کنتراست، ...). */
    fun setPreviewFilter(filter: ColorFilter?) {
        bitmapPaint.colorFilter = filter
        invalidate()
    }

    /** محدوده‌ی برش به‌صورت درصدی از عکس (چپ، بالا، راست، پایین). */
    fun normalizedCrop(): RectF {
        val iw = imageRect.width()
        val ih = imageRect.height()
        if (iw <= 0f || ih <= 0f) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            clampF((crop.left - imageRect.left) / iw, 0f, 1f),
            clampF((crop.top - imageRect.top) / ih, 0f, 1f),
            clampF((crop.right - imageRect.left) / iw, 0f, 1f),
            clampF((crop.bottom - imageRect.top) / ih, 0f, 1f)
        )
    }

    private fun setNormalizedCrop(n: RectF) {
        crop.set(
            imageRect.left + n.left * imageRect.width(),
            imageRect.top + n.top * imageRect.height(),
            imageRect.left + n.right * imageRect.width(),
            imageRect.top + n.bottom * imageRect.height()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val previous = if (imageRect.width() > 0f) normalizedCrop() else null
        layoutImage()
        if (previous != null) setNormalizedCrop(previous) else resetCrop()
    }

    private fun layoutImage() {
        val bmp = bitmap
        if (bmp == null || width <= 0 || height <= 0) {
            imageRect.setEmpty()
            return
        }
        val pad = 14f * density
        val availW = width - 2f * pad
        val availH = height - 2f * pad
        if (availW <= 0f || availH <= 0f) {
            imageRect.setEmpty()
            return
        }
        val s = minOf(availW / bmp.width, availH / bmp.height)
        val iw = bmp.width * s
        val ih = bmp.height * s
        val left = (width - iw) / 2f
        val top = (height - ih) / 2f
        imageRect.set(left, top, left + iw, top + ih)
    }

    private fun resetCrop() {
        crop.set(imageRect)
        applyRatio()
    }

    private fun applyRatio() {
        if (imageRect.width() <= 0f) return
        if (ratio <= 0f) return
        var w = imageRect.width()
        var h = w / ratio
        if (h > imageRect.height()) {
            h = imageRect.height()
            w = h * ratio
        }
        val cx = imageRect.centerX()
        val cy = imageRect.centerY()
        crop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun clampF(v: Float, lo: Float, hi: Float): Float {
        if (hi < lo) return lo
        if (v < lo) return lo
        if (v > hi) return hi
        return v
    }

    private fun hitTest(x: Float, y: Float): Int {
        val slop = 28f * density
        val nearLeft = Math.abs(x - crop.left) <= slop
        val nearRight = Math.abs(x - crop.right) <= slop
        val nearTop = Math.abs(y - crop.top) <= slop
        val nearBottom = Math.abs(y - crop.bottom) <= slop
        if (nearLeft && nearTop) return DRAG_TL
        if (nearRight && nearTop) return DRAG_TR
        if (nearLeft && nearBottom) return DRAG_BL
        if (nearRight && nearBottom) return DRAG_BR
        if (ratio <= 0f) {
            val insideX = x > crop.left && x < crop.right
            val insideY = y > crop.top && y < crop.bottom
            if (nearLeft && insideY) return DRAG_L
            if (nearRight && insideY) return DRAG_R
            if (nearTop && insideX) return DRAG_T
            if (nearBottom && insideX) return DRAG_B
        }
        if (crop.contains(x, y)) return DRAG_MOVE
        return DRAG_NONE
    }

    private fun moveCrop(dx: Float, dy: Float) {
        var nx = dx
        var ny = dy
        if (crop.left + nx < imageRect.left) nx = imageRect.left - crop.left
        if (crop.right + nx > imageRect.right) nx = imageRect.right - crop.right
        if (crop.top + ny < imageRect.top) ny = imageRect.top - crop.top
        if (crop.bottom + ny > imageRect.bottom) ny = imageRect.bottom - crop.bottom
        crop.offset(nx, ny)
    }

    private fun resizeCorner(mode: Int, x: Float, y: Float) {
        val left = (mode == DRAG_TL || mode == DRAG_BL)
        val top = (mode == DRAG_TL || mode == DRAG_TR)
        val ax = if (left) crop.right else crop.left
        val ay = if (top) crop.bottom else crop.top
        val dirX = if (left) -1f else 1f
        val dirY = if (top) -1f else 1f
        val minSize = 56f * density
        var w = maxOf(0f, (x - ax) * dirX)
        var h = maxOf(0f, (y - ay) * dirY)
        val maxW = if (left) ax - imageRect.left else imageRect.right - ax
        val maxH = if (top) ay - imageRect.top else imageRect.bottom - ay
        if (ratio > 0f) {
            w = maxOf(w, h * ratio)
            w = minOf(w, minOf(maxW, maxH * ratio))
            w = maxOf(w, minOf(minSize, minOf(maxW, maxH * ratio)))
            h = w / ratio
        } else {
            w = minOf(maxOf(w, minSize), maxW)
            h = minOf(maxOf(h, minSize), maxH)
        }
        val nl = if (left) ax - w else ax
        val nr = if (left) ax else ax + w
        val nt = if (top) ay - h else ay
        val nb = if (top) ay else ay + h
        crop.set(nl, nt, nr, nb)
    }

    private fun resizeEdge(mode: Int, x: Float, y: Float) {
        val minSize = 56f * density
        when (mode) {
            DRAG_L -> crop.left = clampF(x, imageRect.left, crop.right - minSize)
            DRAG_R -> crop.right = clampF(x, crop.left + minSize, imageRect.right)
            DRAG_T -> crop.top = clampF(y, imageRect.top, crop.bottom - minSize)
            DRAG_B -> crop.bottom = clampF(y, crop.top + minSize, imageRect.bottom)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.width() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                if (dragMode == DRAG_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DRAG_NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (dragMode) {
                    DRAG_MOVE -> moveCrop(dx, dy)
                    DRAG_TL, DRAG_TR, DRAG_BL, DRAG_BR -> resizeCorner(dragMode, event.x, event.y)
                    else -> resizeEdge(dragMode, event.x, event.y)
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DRAG_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float) {
        val len = 22f * density
        canvas.drawLine(x, y, x + sx * len, y, handlePaint)
        canvas.drawLine(x, y, x, y + sy * len, handlePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("ابتدا یک عکس انتخاب کنید", width / 2f, height / 2f, hintPaint)
            return
        }
        if (imageRect.width() <= 0f) return
        canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)

        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, crop.top, dimPaint)
        canvas.drawRect(imageRect.left, crop.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, crop.top, crop.left, crop.bottom, dimPaint)
        canvas.drawRect(crop.right, crop.top, imageRect.right, crop.bottom, dimPaint)

        canvas.drawRect(crop, borderPaint)
        val x1 = crop.left + crop.width() / 3f
        val x2 = crop.left + crop.width() * 2f / 3f
        val y1 = crop.top + crop.height() / 3f
        val y2 = crop.top + crop.height() * 2f / 3f
        canvas.drawLine(x1, crop.top, x1, crop.bottom, gridPaint)
        canvas.drawLine(x2, crop.top, x2, crop.bottom, gridPaint)
        canvas.drawLine(crop.left, y1, crop.right, y1, gridPaint)
        canvas.drawLine(crop.left, y2, crop.right, y2, gridPaint)

        drawCorner(canvas, crop.left, crop.top, 1f, 1f)
        drawCorner(canvas, crop.right, crop.top, -1f, 1f)
        drawCorner(canvas, crop.left, crop.bottom, 1f, -1f)
        drawCorner(canvas, crop.right, crop.bottom, -1f, -1f)
    }
}

/**
 * چیدمانی که اجزا را کنار هم می‌چیند و اگر جا نشد، به خط بعد می‌برد (راست‌به‌چپ هم درست کار می‌کند).
 * برای ردیف گزینه‌ها (چیپ‌ها) استفاده می‌شود تا هیچ گزینه‌ای بیرون از صفحه نماند.
 */
class PhotoFlowLayout(context: Context) : ViewGroup(context) {

    private val gap = (8f * context.resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val specWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = if (mode == View.MeasureSpec.UNSPECIFIED) 100000 else maxOf(0, specWidth - paddingLeft - paddingRight)
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (lineWidth > 0 && lineWidth + gap + cw > maxWidth) {
                totalHeight += lineHeight + gap
                widest = maxOf(widest, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += (if (lineWidth > 0) gap else 0) + cw
            lineHeight = maxOf(lineHeight, ch)
        }
        totalHeight += lineHeight
        widest = maxOf(widest, lineWidth)
        setMeasuredDimension(
            View.resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            View.resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val availWidth = r - l - paddingLeft - paddingRight
        var used = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (used > 0 && used + gap + cw > availWidth) {
                y += lineHeight + gap
                used = 0
                lineHeight = 0
            }
            val offset = if (used > 0) used + gap else 0
            val left = if (rtl) (r - l - paddingRight - offset - cw) else (paddingLeft + offset)
            child.layout(left, y, left + cw, y + ch)
            used = offset + cw
            lineHeight = maxOf(lineHeight, ch)
        }
    }
}
