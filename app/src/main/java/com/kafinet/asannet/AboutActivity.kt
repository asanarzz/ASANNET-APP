package com.kafinet.asannet

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.txtAboutVersion.text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)
    }
}
