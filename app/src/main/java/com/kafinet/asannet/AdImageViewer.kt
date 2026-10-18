package com.kafinet.asannet

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target

/**
 * ImageView با زوم دو انگشتی، جابه‌جایی (اسکرول) روی عکس زوم‌شده و زوم با دوبار لمس.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var baseScale = 1f
    private val maxZoom = 4f
    private var lastX = 0f
    private var lastY = 0f
    private var activePointer = -1

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor).coerceIn(baseScale, baseScale * maxZoom)
                val factor = target / current
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current > baseScale * 1.05f) {
                    resetToFit()
                } else {
                    val factor = (baseScale * 2.5f) / current
                    drawMatrix.postScale(factor, factor, e.x, e.y)
                    applyMatrix()
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun resetToFit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        baseScale = minOf(width / dw, height / dh)
        drawMatrix.reset()
        drawMatrix.postScale(baseScale, baseScale)
        drawMatrix.postTranslate((width - dw * baseScale) / 2f, (height - dh * baseScale) / 2f)
        imageMatrix = drawMatrix
    }

    private fun applyMatrix() {
        fixTranslation()
        imageMatrix = drawMatrix
    }

    private fun fixTranslation() {
        val d = drawable ?: return
        drawMatrix.getValues(values)
        val sc = values[Matrix.MSCALE_X]
        val imgW = d.intrinsicWidth * sc
        val imgH = d.intrinsicHeight * sc
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        tx = if (imgW <= width) (width - imgW) / 2f else tx.coerceIn(width - imgW, 0f)
        ty = if (imgH <= height) (height - imgH) / 2f else ty.coerceIn(height - imgH, 0f)
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        drawMatrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val zoomed = currentScale() > baseScale * 1.02f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activePointer = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(zoomed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && zoomed && event.pointerCount == 1) {
                    val idx = event.findPointerIndex(activePointer)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        drawMatrix.postTranslate(x - lastX, y - lastY)
                        applyMatrix()
                        lastX = x
                        lastY = y
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (event.pointerCount == 1) {
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointer) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointer = event.getPointerId(newIndex)
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activePointer = -1
            }
        }
        return true
    }
}

/**
 * نمایش تمام‌صفحه‌ی عکس‌های آگهی: زوم، جابه‌جایی و ورق زدن بین عکس‌ها.
 */
class AdImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URLS = "viewer_urls"
        const val EXTRA_INDEX = "viewer_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: ArrayList()
        if (urls.isEmpty()) {
            finish()
            return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1)
        val density = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val list = RecyclerView(this)
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.layoutManager = lm
        PagerSnapHelper().attachToRecyclerView(list)
        list.adapter = ViewerAdapter(urls)
        root.addView(
            list,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val counter = TextView(this)
        counter.setTextColor(Color.WHITE)
        counter.textSize = 16f
        counter.text = (startIndex + 1).toString() + " / " + urls.size
        val counterLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        counterLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        counterLp.topMargin = (40 * density).toInt()
        root.addView(counter, counterLp)

        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 24f
        val pad = (14 * density).toInt()
        close.setPadding(pad, pad, pad, pad)
        close.setOnClickListener { finish() }
        val closeLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        closeLp.gravity = Gravity.TOP or Gravity.START
        closeLp.topMargin = (28 * density).toInt()
        root.addView(close, closeLp)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = lm.findFirstCompletelyVisibleItemPosition()
                    if (pos >= 0) {
                        counter.text = (pos + 1).toString() + " / " + urls.size
                    }
                }
            }
        })

        setContentView(root)
        list.scrollToPosition(startIndex)
    }
}

private class ViewerAdapter(private val urls: List<String>) :
    RecyclerView.Adapter<ViewerAdapter.VH>() {

    class VH(val view: ZoomImageView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ZoomImageView(parent.context)
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.view.context)
            .load(urls[position])
            .override(Target.SIZE_ORIGINAL)
            .into(holder.view)
    }

    override fun onViewRecycled(holder: VH) {
        Glide.with(holder.view.context).clear(holder.view)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = urls.size
}
