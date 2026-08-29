package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ActivityMediaDetailBinding

/**
 * صفحه‌ی جزئیات یک محتوا: گالری عکس (اگه چندتا باشه، قابل ورق‌زدن)، توضیحات،
 * و در صورت وجود، دکمه‌ی باز کردن لینک/دانلود فایل جداگانه.
 */
class MediaDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_IMAGES = "extra_images" // ArrayList<String>
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var binding: ActivityMediaDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val images = intent.getStringArrayListExtra(EXTRA_IMAGES).orEmpty()
        val linkUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        binding.txtTitle.text = title
        binding.txtHeadline.text = title

        binding.txtDescription.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
        binding.txtDescription.text = description

        binding.btnBack.setOnClickListener { finish() }

        if (images.isNotEmpty()) {
            binding.progress.visibility = View.GONE
            binding.recyclerGallery.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerGallery.adapter = GalleryImageAdapter(images)
            PagerSnapHelper().attachToRecyclerView(binding.recyclerGallery)

            if (images.size > 1) {
                binding.txtPageIndicator.visibility = View.VISIBLE
                updatePageIndicator(0, images.size)
                binding.recyclerGallery.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val pos = lm.findFirstVisibleItemPosition()
                        if (pos >= 0) updatePageIndicator(pos, images.size)
                    }
                })
            }

            // اگه فقط یک عکس بود و لینک جداگانه‌ای هم نبود، دکمه‌ی دانلود همون عکس رو نشون بده
            if (images.size == 1 && linkUrl.isBlank()) {
                binding.btnDownload.visibility = View.VISIBLE
                binding.btnDownload.setOnClickListener {
                    if (DownloadHelper.ensureStoragePermission(this)) {
                        DownloadHelper.downloadUrl(this, images[0], title)
                    }
                }
            }
        } else {
            binding.galleryFrame.visibility = View.GONE
        }

        if (linkUrl.isNotBlank()) {
            binding.btnOpenLink.visibility = View.VISIBLE
            binding.btnOpenLink.setOnClickListener { openLink(linkUrl, title) }
        }
    }

    private fun updatePageIndicator(position: Int, total: Int) {
        binding.txtPageIndicator.text = getString(R.string.page_indicator_format, position + 1, total)
    }

    private fun openLink(url: String, title: String) {
        val lowerUrl = url.substringBefore("?").substringBefore("#").lowercase()
        val isDirectFile = lowerUrl.endsWith(".apk") || lowerUrl.endsWith(".pdf") ||
            lowerUrl.endsWith(".zip") || lowerUrl.endsWith(".mp3") || lowerUrl.endsWith(".mp4")

        if (isDirectFile) {
            if (DownloadHelper.ensureStoragePermission(this)) {
                DownloadHelper.downloadUrl(this, url, title)
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.EXTRA_URL, url)
            intent.putExtra(WebViewActivity.EXTRA_TITLE, title)
            intent.putExtra(WebViewActivity.EXTRA_ALLOW_DOWNLOAD, true)
            startActivity(intent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
