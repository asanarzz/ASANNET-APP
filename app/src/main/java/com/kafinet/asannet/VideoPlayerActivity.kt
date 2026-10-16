package com.kafinet.asannet

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val hideUiRunnable = Runnable {
        binding.header.visibility = View.GONE
        applyImmersive()
    }

    @Suppress("DEPRECATION")
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

        // دکمه‌های بالا از زیر فرورفتگی دوربین و نوارهای سیستم بیرون بیایند
        val basePadding = binding.header.paddingTop
        binding.header.setOnApplyWindowInsetsListener { v, insets ->
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            if (Build.VERSION.SDK_INT >= 28) {
                val cutout = insets.displayCutout
                if (cutout != null) {
                    left = maxOf(left, cutout.safeInsetLeft)
                    top = maxOf(top, cutout.safeInsetTop)
                    right = maxOf(right, cutout.safeInsetRight)
                }
            }
            v.setPadding(basePadding + left, basePadding + top, basePadding + right, basePadding)
            insets
        }

        binding.txtTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        // برگشت (دکمه‌ی گوشی یا فلش بالا) همیشه مستقیم پخش‌کننده را می‌بندد
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closePlayer()
            }
        })
        binding.btnBack.setOnClickListener { closePlayer() }

        // پخش‌کننده همیشه عمودی باز می‌شود و فقط با دکمه‌ی چرخش می‌چرخد
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.btnRotate.setOnClickListener {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            showUiTemporarily()
        }
        // دکمه‌ی اندازه‌ی تصویر: کل تصویر ⇄ پر کردن تمام صفحه
        binding.btnZoom.setOnClickListener {
            val fill = !binding.videoView.zoomToFill
            player?.let {
                try {
                    binding.videoView.setVideoSize(it.videoWidth, it.videoHeight)
                } catch (e: Exception) {
                }
            }
            binding.videoView.zoomToFill = fill
            Toast.makeText(
                this,
                if (fill) "تصویر، کل صفحه را پر کرد" else "اندازه‌ی اصلی تصویر",
                Toast.LENGTH_SHORT
            ).show()
            showUiTemporarily()
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

        // با لمس صفحه، نوار بالا و دکمه‌های گوشی چند ثانیه دیده می‌شوند.
        // (VideoView فقط شروع لمس را می‌فرستد، پس باید روی ACTION_DOWN منتظر بمانیم.)
        binding.videoView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                showUiTemporarily()
            }
            false
        }

        val mediaController = PlayerMediaController(this)
        mediaController.onBack = { closePlayer() }
        binding.videoView.setMediaController(mediaController)

        binding.videoView.setOnPreparedListener { mp ->
            player = mp
            binding.progress.visibility = View.GONE
            binding.videoView.setVideoSize(mp.videoWidth, mp.videoHeight)
            binding.videoView.start()
        }
        // اندازه‌ی تصویر شبکه‌های زنده گاهی دیرتر مشخص می‌شود
        binding.videoView.setOnInfoListener { mp, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                binding.videoView.setVideoSize(mp.videoWidth, mp.videoHeight)
            }
            false
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

        showUiTemporarily()
    }

    private fun closePlayer() {
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    /** نوار بالا و نوارهای سیستم (از جمله دکمه‌ی برگشت گوشی) را چند ثانیه نشان می‌دهد. */
    private fun showUiTemporarily() {
        binding.header.visibility = View.VISIBLE
        showSystemBars()
        handler.removeCallbacks(hideUiRunnable)
        handler.postDelayed(hideUiRunnable, 4000)
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
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
        player = null
        binding.videoView.stopPlayback()
        super.onDestroy()
    }
}
