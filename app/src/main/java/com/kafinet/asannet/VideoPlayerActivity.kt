package com.kafinet.asannet

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private val hideHeaderRunnable = Runnable { binding.header.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تصویر تا لبه‌ی صفحه (حتی کنار فرورفتگی دوربین) ادامه پیدا کند
        if (Build.VERSION.SDK_INT >= 28) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        // دکمه‌های بالا از زیر فرورفتگی دوربین بیرون بیایند
        val basePadding = binding.header.paddingTop
        binding.header.setOnApplyWindowInsetsListener { v, insets ->
            var left = 0
            var top = 0
            var right = 0
            if (Build.VERSION.SDK_INT >= 28) {
                val cutout = insets.displayCutout
                if (cutout != null) {
                    left = cutout.safeInsetLeft
                    top = cutout.safeInsetTop
                    right = cutout.safeInsetRight
                }
            }
            v.setPadding(basePadding + left, basePadding + top, basePadding + right, basePadding)
            insets
        }

        binding.txtTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        binding.btnBack.setOnClickListener { finish() }

        // چرخش صفحه: به‌صورت پیش‌فرض با چرخاندن گوشی می‌چرخد (حتی اگر چرخش خودکار گوشی خاموش باشد)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        // دکمه‌ی چرخش: بین حالت افقی و عمودی جابه‌جا می‌کند
        binding.btnRotate.setOnClickListener {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            showHeaderTemporarily()
        }
        // دکمه‌ی اندازه‌ی تصویر: کل تصویر ⇄ پر کردن تمام صفحه
        binding.btnZoom.setOnClickListener {
            val fill = !binding.videoView.zoomToFill
            binding.videoView.zoomToFill = fill
            Toast.makeText(
                this,
                if (fill) "تصویر، کل صفحه را پر کرد" else "اندازه‌ی اصلی تصویر",
                Toast.LENGTH_SHORT
            ).show()
            showHeaderTemporarily()
        }
        // دکمه‌ی ارسال به تلویزیون (DLNA)
        binding.btnCast.setOnClickListener {
            binding.videoView.pause()
            DlnaCaster.showDevicePicker(this, url, binding.txtTitle.text.toString())
        }
        binding.btnDownload.setOnClickListener {
            if (DownloadHelper.ensureStoragePermission(this)) {
                DownloadHelper.downloadUrl(this, url, binding.txtTitle.text.toString())
            }
        }

        // با لمس صفحه، نوار بالا نشان داده یا مخفی می‌شود (کنترل‌های پخش را خود MediaController نشان می‌دهد)
        binding.videoView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (binding.header.visibility == View.VISIBLE) {
                    handler.removeCallbacks(hideHeaderRunnable)
                    binding.header.visibility = View.GONE
                } else {
                    showHeaderTemporarily()
                }
            }
            false
        }

        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)

        binding.videoView.setOnPreparedListener { mp ->
            binding.progress.visibility = View.GONE
            binding.videoView.setVideoSize(mp.videoWidth, mp.videoHeight)
            mp.setOnVideoSizeChangedListener { _, w, h -> binding.videoView.setVideoSize(w, h) }
            binding.videoView.start()
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            binding.progress.visibility = View.GONE
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            true
        }

        try {
            binding.videoView.setVideoURI(Uri.parse(url))
        } catch (e: Exception) {
            binding.progress.visibility = View.GONE
        }

        showHeaderTemporarily()
    }

    /** نوار بالا را نشان می‌دهد و بعد از چند ثانیه دوباره مخفی می‌کند. */
    private fun showHeaderTemporarily() {
        binding.header.visibility = View.VISIBLE
        handler.removeCallbacks(hideHeaderRunnable)
        handler.postDelayed(hideHeaderRunnable, 4000)
    }

    /** نوار وضعیت و دکمه‌های پایین گوشی را مخفی می‌کند تا ویدیو تمام‌صفحه شود. */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.videoView.stopPlayback()
        super.onDestroy()
    }
}
