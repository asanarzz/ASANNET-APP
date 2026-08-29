package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ActivityGalleryDetailBinding

/**
 * صفحه‌ی جزئیات یک آیتم چندعکسی: گالری قابل‌سوایپ عکس‌ها در بالا، عنوان و توضیحات،
 * و در صورت وجود، یک دکمه برای باز کردن لینک یا دانلود فایل ضمیمه.
 */
class GalleryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_IMAGES = "extra_images"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_IS_FILE = "extra_is_file"
    }

    private lateinit var binding: ActivityGalleryDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val images = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: arrayListOf()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val isFile = intent.getBooleanExtra(EXTRA_IS_FILE, false)

        binding.txtTitle.text = title
        binding.txtHeadline.text = title
        binding.txtDescription.text = description
        binding.txtDescription.visibility = if (description.isBlank()) View.GONE else View.VISIBLE

        binding.btnBack.setOnClickListener { finish() }

        setupImageGallery(images)

        if (url.isNotBlank()) {
            binding.btnAction.visibility = View.VISIBLE
            binding.btnAction.text = getString(if (isFile) R.string.btn_download else R.string.open_in_browser)
            binding.btnAction.setOnClickListener {
                if (isFile) {
                    if (DownloadHelper.ensureStoragePermission(this)) {
                        DownloadHelper.downloadUrl(this, url, title)
                    }
                } else {
                    val intent = Intent(this, WebViewActivity::class.java)
                    intent.putExtra(WebViewActivity.EXTRA_URL, url)
                    intent.putExtra(WebViewActivity.EXTRA_TITLE, title)
                    startActivity(intent)
                }
            }
        }
    }

    private fun setupImageGallery(images: List<String>) {
        binding.recyclerImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerImages.adapter = GalleryImageAdapter(images)
        LinearSnapHelper().attachToRecyclerView(binding.recyclerImages)

        if (images.size <= 1) {
            binding.txtImageCounter.visibility = View.GONE
        } else {
            updateImageCounter(0, images.size)
            binding.recyclerImages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = rv.layoutManager as LinearLayoutManager
                    val position = layoutManager.findFirstVisibleItemPosition()
                    if (position >= 0) updateImageCounter(position, images.size)
                }
            })
        }
    }

    private fun updateImageCounter(position: Int, total: Int) {
        binding.txtImageCounter.text = getString(R.string.image_counter_format, position + 1, total)
    }
}
