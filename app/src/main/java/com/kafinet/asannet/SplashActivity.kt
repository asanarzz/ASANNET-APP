package com.kafinet.asannet

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    // ویدیو با صدا پخش می‌شود؛ برای بی‌صدا کردن، این را false کنید
    private val playSound = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ویدیوی شروع برنامه (res/raw/splash_video.mp4)
        val video = binding.vidSplash
        video.setVideoURI(Uri.parse("android.resource://" + packageName + "/" + R.raw.splash_video))
        video.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = false
            if (!playSound) mp.setVolume(0f, 0f)
        }
        // تا اولین فریم ویدیو نشان داده نشده، عکس اولین فریم دیده می‌شود (تا صفحه‌ی سیاه دیده نشود)
        video.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                binding.imgSplashCover.visibility = View.GONE
            }
            false
        }
        video.setOnCompletionListener { goNext() }
        video.setOnErrorListener { _, _, _ ->
            goNext()
            true
        }
        video.start()

        // با لمس صفحه می‌شود ویدیو را رد کرد
        binding.root.setOnClickListener { goNext() }

        // اگر به هر دلیل ویدیو تمام نشد، بعد از ۸ ثانیه وارد برنامه شود
        handler.postDelayed({ goNext() }, 8000)
    }

    private fun goNext() {
        if (navigated) return
        navigated = true
        val next = if (SessionManager.isRegistered(this)) MainActivity::class.java else RegistrationActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
