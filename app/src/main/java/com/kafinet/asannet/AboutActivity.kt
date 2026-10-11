package com.kafinet.asannet

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
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
        showBodyWithChannelLink()
    }

    /** متن «درباره» را نشان می‌دهد و آدرس کانال (@ASANNETT) را قابل لمس می‌کند. */
    private fun showBodyWithChannelLink() {
        val body = getString(R.string.about_body)
        val spannable = SpannableString(body)
        val handle = "@ASANNETT"
        var start = body.indexOf(handle)
        while (start >= 0) {
            val end = start + handle.length
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openChannel()
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = body.indexOf(handle, end)
        }
        binding.txtAboutBody.text = spannable
        binding.txtAboutBody.movementMethod = LinkMovementMethod.getInstance()
        binding.txtAboutBody.setLinkTextColor(Color.parseColor("#5B63F5"))
    }

    private fun openChannel() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://eitaa.com/asannett")))
        } catch (e: Exception) {
            Toast.makeText(this, "باز کردن کانال ممکن نشد", Toast.LENGTH_SHORT).show()
        }
    }
}
