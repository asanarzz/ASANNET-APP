package com.kafinet.asannet

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivityVideoDetailBinding

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_VIDEO_URL = "extra_video_url"
    }

    private lateinit var binding: ActivityVideoDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val posterUrl = intent.getStringExtra(EXTRA_POSTER_URL).orEmpty()
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()

        binding.txtHeaderTitle.text = title
        binding.txtTitle.text = title
        binding.txtDescription.text = description
        binding.txtDescription.visibility =
            if (description.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        Glide.with(this)
            .load(posterUrl)
            .placeholder(R.drawable.ic_video)
            .into(binding.imgPoster)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDownload.setOnClickListener {
            if (DownloadHelper.ensureStoragePermission(this)) {
                DownloadHelper.downloadUrl(this, resolveUrl(videoUrl), title)
            }
        }
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
