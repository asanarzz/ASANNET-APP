package com.kafinet.asannet

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.txtSettingsVersion.text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)

        binding.btnClearCache.setOnClickListener {
            Glide.get(this).clearMemory()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { Glide.get(this@SettingsActivity).clearDiskCache() }
                Toast.makeText(this@SettingsActivity, R.string.settings_clear_cache_done, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
