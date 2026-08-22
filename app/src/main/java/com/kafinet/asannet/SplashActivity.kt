package com.kafinet.asannet

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // انیمیشن ورود لوگو: بزرگ‌شدن با کمی جهش (حس سه‌بعدی) همراه با محو‌شدن به داخل
        val logo = binding.imgSplashLogo
        logo.scaleX = 0.4f
        logo.scaleY = 0.4f
        logo.alpha = 0f

        val scaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0.4f, 1f)
        val scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.4f, 1f)
        val alpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 900
            interpolator = OvershootInterpolator(1.6f)
            start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (SessionManager.isRegistered(this)) MainActivity::class.java else RegistrationActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, 3000)
    }
}
