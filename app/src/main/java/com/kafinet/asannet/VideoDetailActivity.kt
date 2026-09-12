package com.kafinet.asannet

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivityVideoDetailBinding

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_LINK_LABELS = "extra_link_labels"
        const val EXTRA_LINK_URLS = "extra_link_urls"
    }

    private lateinit var binding: ActivityVideoDetailBinding
    private lateinit var itemTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        itemTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val posterUrl = intent.getStringExtra(EXTRA_POSTER_URL).orEmpty()
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val linkLabels = intent.getStringArrayListExtra(EXTRA_LINK_LABELS) ?: arrayListOf()
        val linkUrls = intent.getStringArrayListExtra(EXTRA_LINK_URLS) ?: arrayListOf()

        binding.txtHeaderTitle.text = itemTitle
        binding.txtTitle.text = itemTitle
        binding.txtDescription.text = description
        binding.txtDescription.visibility =
            if (description.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        Glide.with(this)
            .load(posterUrl)
            .placeholder(R.drawable.ic_video)
            .into(binding.imgPoster)

        binding.btnBack.setOnClickListener { finish() }

        if (linkUrls.isNotEmpty()) {
            for (i in linkUrls.indices) {
                val label = linkLabels.getOrNull(i).orEmpty()
                addDownloadButton(label, linkUrls[i])
            }
        } else if (videoUrl.isNotBlank()) {
            addDownloadButton("", videoUrl)
        }
    }

    private fun addDownloadButton(label: String, url: String) {
        val density = resources.displayMetrics.density
        val button = Button(this)
        button.text = label.ifBlank { getString(R.string.btn_download) }
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)))
        button.setTextColor(ContextCompat.getColor(this, R.color.white))
        button.textSize = 15f

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (50 * density).toInt()
        )
        if (binding.layoutDownloadLinks.childCount > 0) {
            params.topMargin = (10 * density).toInt()
        }

        button.setOnClickListener {
            if (DownloadHelper.ensureStoragePermission(this)) {
                val downloadTitle = if (label.isBlank()) itemTitle else "$itemTitle - $label"
                DownloadHelper.downloadUrl(this, resolveUrl(url), downloadTitle)
            }
        }

        binding.layoutDownloadLinks.addView(button, params)
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
