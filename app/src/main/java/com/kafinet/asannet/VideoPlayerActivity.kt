package com.kafinet.asannet

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)

        binding.videoView.setOnPreparedListener {
            binding.progress.visibility = android.view.View.GONE
            binding.videoView.start()
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            binding.progress.visibility = android.view.View.GONE
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            true
        }

        try {
            binding.videoView.setVideoURI(Uri.parse(url))
        } catch (e: Exception) {
            binding.progress.visibility = android.view.View.GONE
        }
    }

    override fun onDestroy() {
        binding.videoView.stopPlayback()
        super.onDestroy()
    }
}
