package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivityContactBinding

class ContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // این مقادیر فعلاً خالی‌اند؛ به‌محض دریافت شماره/آیدی واقعی از صاحب برنامه پر می‌شوند.
        val phone = ""
        val eitaaUsername = "ASAN_NETT"
        val email = ""

        if (phone.isNotBlank()) {
            binding.txtPhoneValue.text = phone
            binding.rowPhone.setOnClickListener {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        }
        if (eitaaUsername.isNotBlank()) {
            binding.txtEitaaValue.text = "@$eitaaUsername"
            binding.rowEitaa.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://eitaa.com/$eitaaUsername")))
            }
        }
        if (email.isNotBlank()) {
            binding.txtEmailValue.text = email
            binding.rowEmail.setOnClickListener {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
            }
        }
    }
}
