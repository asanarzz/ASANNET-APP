package com.kafinet.asannet

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // لوگو با سرعت دور خودش می‌چرخد و به‌تدریج می‌ایستد
        val logo = binding.imgSplashLogo
        val rotate = ObjectAnimator.ofFloat(logo, "rotation", 0f, 1080f)
        rotate.duration = 1400
        rotate.interpolator = DecelerateInterpolator(1.8f)
        rotate.start()

        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (SessionManager.isRegistered(this)) MainActivity::class.java else RegistrationActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, 3000)
    }
}
