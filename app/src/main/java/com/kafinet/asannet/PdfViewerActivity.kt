package com.kafinet.asannet

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * نمایشگر PDF داخل خود برنامه:
 * فایل را (فقط بار اول) دانلود و داخل حافظه‌ی موقت برنامه نگه می‌دارد، بعد با
 * PdfRenderer خودِ اندروید صفحه‌به‌صفحه نشان می‌دهد. نیازی به گوگل یا برنامه‌ی دیگر ندارد.
 * جابه‌جایی بین صفحه‌ها با کشیدن به چپ و راست، زوم با دو انگشت یا دوبار لمس.
 */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var destroyed = false

    private lateinit var url: String
    private lateinit var title: String

    private lateinit var counterView: TextView
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var statusBox: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var recycler: RecyclerView
    private var pageCount = 0

    private val bitmapCache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL).orEmpty().trim().replace(" ", "%20")
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        buildUi()
        load()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1F2937"))

        // نوار بالا
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))

        val back = TextView(this)
        back.text = "←"
        back.setTextColor(Color.WHITE)
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        back.gravity = Gravity.CENTER
        back.setPadding(dp(14), dp(6), dp(14), dp(6))
        back.setOnClickListener { finish() }
        bar.addView(back)

        val titleView = TextView(this)
        titleView.text = title
        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        counterView = TextView(this)
        counterView.setTextColor(Color.WHITE)
        counterView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        counterView.setTypeface(counterView.typeface, android.graphics.Typeface.BOLD)
        counterView.textDirection = View.TEXT_DIRECTION_LTR
        counterView.gravity = Gravity.CENTER


        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // بدنه
        val body = FrameLayout(this)

        recycler = RecyclerView(this)
        recycler.layoutDirection = View.LAYOUT_DIRECTION_LTR
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recycler.visibility = View.GONE
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(recycler)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val v = snap.findSnapView(rv.layoutManager) ?: return
                    val pos = rv.getChildAdapterPosition(v)
                    if (pos >= 0) updateCounter(pos)
                }
            }
        })
        body.addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusBox = LinearLayout(this)
        statusBox.orientation = LinearLayout.VERTICAL
        statusBox.gravity = Gravity.CENTER
        statusBox.setPadding(dp(32), dp(32), dp(32), dp(32))

        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        statusView.gravity = Gravity.CENTER
        statusBox.addView(statusView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.isIndeterminate = true
        val pbParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pbParams.topMargin = dp(16)
        statusBox.addView(progressBar, pbParams)

        retryButton = Button(this)
        retryButton.text = "تلاش دوباره"
        retryButton.visibility = View.GONE
        retryButton.setOnClickListener { load() }
        val rbParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rbParams.topMargin = dp(16)
        statusBox.addView(retryButton, rbParams)

        body.addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // نوار پایین: دکمه‌های بزرگ کوچک‌نمایی / بزرگ‌نمایی و شماره‌ی صفحه
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.setBackgroundColor(Color.parseColor("#111827"))
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        bottomBar.visibility = View.GONE

        val zoomOut = makeZoomButton("−")
        zoomOut.setOnClickListener { zoomBy(1f / 1.5f) }
        val zoomIn = makeZoomButton("+")
        zoomIn.setOnClickListener { zoomBy(1.5f) }

        bottomBar.addView(zoomOut, LinearLayout.LayoutParams(0, dp(60), 1f))
        bottomBar.addView(counterView, LinearLayout.LayoutParams(0, dp(60), 1.4f))
        bottomBar.addView(zoomIn, LinearLayout.LayoutParams(0, dp(60), 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun makeZoomButton(label: String): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        b.setTextColor(Color.WHITE)
        b.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        )
        return b
    }

    /** صفحه‌ای که الان روی صفحه است. */
    private fun currentPhoto(): PhotoView? {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return null
        var pos = lm.findFirstCompletelyVisibleItemPosition()
        if (pos < 0) pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return null
        return lm.findViewByPosition(pos) as? PhotoView
    }

    private fun zoomBy(factor: Float) {
        val photo = currentPhoto() ?: return
        val target = (photo.scale * factor).coerceIn(photo.minimumScale, photo.maximumScale)
        photo.setScale(target, true)
    }

    private fun updateCounter(index: Int) {
        counterView.text = "${index + 1} / $pageCount"
    }

    private fun showLoading(text: String) {
        statusBox.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        bottomBar.visibility = View.GONE
        statusView.text = text
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        retryButton.visibility = View.GONE
    }

    private fun showError(text: String) {
        statusBox.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        bottomBar.visibility = View.GONE
        statusView.text = text
        progressBar.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
    }

    private fun load() {
        showLoading("در حال دریافت فایل…")
        Thread {
            val file = try { obtainFile() } catch (e: Exception) { null }
            handler.post {
                if (destroyed) return@post
                if (file == null) {
                    showError("دریافت فایل ممکن نشد. اینترنت را بررسی کن و دوباره تلاش کن.")
                } else {
                    openPdf(file)
                }
            }
        }.start()
    }

    /** فایل را (اگر قبلاً نیامده) دانلود می‌کند و داخل حافظه‌ی موقت برنامه نگه می‌دارد. */
    private fun obtainFile(): File? {
        val dir = File(cacheDir, "pdf_view")
        dir.mkdirs()
        // فایل‌های قدیمی‌تر از ۷ روز پاک می‌شوند
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < weekAgo) it.delete() }

        val target = File(dir, Integer.toHexString(url.hashCode()) + ".pdf")
        if (target.exists() && target.length() > 0L) return target

        val part = File(dir, target.name + ".part")
        try {
            if (url.startsWith("file:///android_asset/")) {
                assets.open(url.removePrefix("file:///android_asset/")).use { input ->
                    part.outputStream().use { out -> input.copyTo(out) }
                }
            } else {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                connection.connect()
                if (connection.responseCode !in 200..299) return null
                val total = connection.contentLengthLong
                var done = 0L
                var lastUpdate = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (!destroyed) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastUpdate > 200) {
                                lastUpdate = now
                                val percent = (done * 100 / total).toInt()
                                handler.post {
                                    if (!destroyed) {
                                        progressBar.isIndeterminate = false
                                        progressBar.progress = percent
                                        statusView.text = "در حال دریافت فایل… $percent٪"
                                    }
                                }
                            }
                        }
                    }
                }
                if (destroyed) { part.delete(); return null }
                if (total > 0 && done < total) { part.delete(); return null }
            }
            if (target.exists()) target.delete()
            return if (part.renameTo(target)) target else null
        } catch (e: Exception) {
            part.delete()
            return null
        }
    }

    private fun openPdf(file: File) {
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(descriptor)
            if (r.pageCount <= 0) {
                r.close()
                descriptor.close()
                throw IllegalStateException("empty pdf")
            }
            synchronized(lock) {
                pfd = descriptor
                renderer = r
            }
            pageCount = r.pageCount
            statusBox.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            recycler.adapter = PageAdapter(pageCount)
            updateCounter(0)
        } catch (e: Exception) {
            file.delete()
            showError("این فایل قابل نمایش نیست.")
        }
    }

    private inner class PageHolder(val view: PhotoView) : RecyclerView.ViewHolder(view)

    private inner class PageAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val photo = PhotoView(parent.context)
            photo.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            photo.maximumScale = 6f
            photo.mediumScale = 2.5f
            return PageHolder(photo)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.view.tag = position
            val cached = bitmapCache.get(position)
            if (cached != null) {
                holder.view.setImageBitmap(cached)
            } else {
                holder.view.setImageDrawable(null)
                renderPage(position, holder.view)
            }
        }

        override fun getItemCount(): Int = count
    }

    private fun renderPage(position: Int, view: PhotoView) {
        renderExecutor.execute {
            if (destroyed) return@execute
            val bitmap: Bitmap? = try {
                synchronized(lock) {
                    val r = renderer
                    if (r == null) {
                        null
                    } else {
                        val page = r.openPage(position)
                        try {
                            var scale = (resources.displayMetrics.widthPixels * 1.5f).coerceAtMost(1800f) / page.width
                            if (page.height * scale > 3600f) scale = 3600f / page.height
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        } finally {
                            page.close()
                        }
                    }
                }
            } catch (e: Throwable) {
                null
            }
            if (bitmap != null) {
                bitmapCache.put(position, bitmap)
                handler.post {
                    if (!destroyed && view.tag == position) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        renderExecutor.shutdownNow()
        synchronized(lock) {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            renderer = null
            pfd = null
        }
        super.onDestroy()
    }
}
