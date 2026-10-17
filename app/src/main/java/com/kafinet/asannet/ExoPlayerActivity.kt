package com.kafinet.asannet

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kafinet.asannet.databinding.ActivityExoPlayerBinding

/**
 * پخش‌کننده‌ی جدید بر پایه‌ی ExoPlayer (پخش‌کننده‌ی رسمی گوگل).
 * ریدایرکت بین سرورها، HLS و خطاهای شبکه را بهتر از VideoView مدیریت می‌کند.
 * پخش‌کننده‌ی قبلی (VideoPlayerActivity) دست‌نخورده مانده؛ با تغییر USE_EXO_PLAYER در ContentOpener می‌شود به آن برگشت.
 */
@OptIn(UnstableApi::class)
class ExoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExoPlayerBinding
    private var player: ExoPlayer? = null
    private var url: String = ""
    private var triedHlsMime = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تصویر تا لبه‌ی صفحه (حتی کنار فرورفتگی دوربین) ادامه پیدا کند
        if (Build.VERSION.SDK_INT >= 28) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        binding = ActivityExoPlayerBinding.inflate(layoutInflater)
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

        binding.txtTitle.text = intent.getStringExtra(VideoPlayerActivity.EXTRA_TITLE).orEmpty()
        url = intent.getStringExtra(VideoPlayerActivity.EXTRA_URL).orEmpty()

        // برگشت (دکمه‌ی گوشی یا فلش بالا) همیشه مستقیم پخش‌کننده را می‌بندد
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
        binding.btnBack.setOnClickListener { finish() }

        // پخش‌کننده همیشه عمودی باز می‌شود و فقط با دکمه‌ی چرخش می‌چرخد
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.btnRotate.setOnClickListener {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        // دکمه‌ی اندازه‌ی تصویر: کل تصویر ⇄ پر کردن تمام صفحه
        binding.btnZoom.setOnClickListener {
            val fill = binding.playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            binding.playerView.resizeMode = if (fill) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            Toast.makeText(
                this,
                if (fill) "تصویر، کل صفحه را پر کرد" else "اندازه‌ی اصلی تصویر",
                Toast.LENGTH_SHORT
            ).show()
        }
        // دکمه‌ی ارسال به تلویزیون (DLNA)
        binding.btnCast.setOnClickListener {
            player?.pause()
            DlnaCaster.showDevicePicker(this, url, binding.txtTitle.text.toString())
        }
        binding.btnDownload.setOnClickListener {
            if (DownloadHelper.ensureStoragePermission(this)) {
                DownloadHelper.downloadUrl(this, url, binding.txtTitle.text.toString())
            }
        }

        // نوار بالا و دکمه‌های گوشی همزمان با کنترل‌های پخش دیده یا مخفی می‌شوند (با لمس صفحه)
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.header.visibility = visibility
                if (visibility == View.VISIBLE) showSystemBars() else applyImmersive()
            }
        )

        // با لمس پیام خطا، دوباره تلاش می‌کند
        binding.txtError.setOnClickListener {
            binding.txtError.visibility = View.GONE
            player?.let {
                it.prepare()
                it.play()
            }
        }

        initPlayer()
    }

    private fun initPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        player = exo
        binding.playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    binding.txtError.visibility = View.GONE
                }
            }
        })

        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun handleError(error: PlaybackException) {
        val exo = player ?: return

        // در شبکه‌های زنده، اگر از پخش عقب افتاد، به لحظه‌ی زنده برگرد
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }

        // لینک بدون پسوند مشخص: یک بار به‌عنوان HLS امتحان کن
        val unknownFormat =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (unknownFormat && !triedHlsMime) {
            triedHlsMime = true
            exo.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            exo.prepare()
            exo.play()
            return
        }

        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "اتصال به سرور شبکه برقرار نشد. اینترنت یا فیلترشکن را بررسی کنید."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "سرور این لینک را نپذیرفت. ممکن است لینک منقضی شده یا فقط برای IP ایران باز شود."
            else -> "پخش این لینک ممکن نشد."
        }
        binding.txtError.text = reason + "\n(" + error.errorCodeName + ")\nبرای تلاش دوباره لمس کنید"
        binding.txtError.visibility = View.VISIBLE
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
        if (hasFocus && binding.header.visibility != View.VISIBLE) applyImmersive()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
