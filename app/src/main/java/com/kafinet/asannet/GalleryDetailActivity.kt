package com.kafinet.asannet

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.kafinet.asannet.databinding.ActivityGalleryDetailBinding

/**
 * صفحه‌ی جزئیات یک آیتم چندعکسی: گالری قابل‌سوایپ عکس‌ها در بالا (۹۰٪ صفحه)،
 * و نوار توضیحات در ۱۰٪ پایین که با لمس، کامل باز می‌شه.
 * در صورت وجود، یک دکمه برای باز کردن لینک یا دانلود فایل ضمیمه.
 */
class GalleryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_IMAGES = "extra_images"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_IS_FILE = "extra_is_file"

        private const val IMAGE_WEIGHT_COLLAPSED = 9f
        private const val CAPTION_WEIGHT_COLLAPSED = 1f
        private const val IMAGE_WEIGHT_EXPANDED = 3f
        private const val CAPTION_WEIGHT_EXPANDED = 7f
        private const val WEIGHT_ANIM_DURATION = 220L
    }

    private lateinit var binding: ActivityGalleryDetailBinding
    private var isCaptionExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val images = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: arrayListOf()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val isFile = intent.getBooleanExtra(EXTRA_IS_FILE, false)

        binding.txtTitle.text = forceEnglishDigits(title)
        binding.txtHeadline.text = forceEnglishDigits(title)
        binding.txtDescription.text = forceEnglishDigits(description)
        binding.txtDescription.visibility = if (description.isBlank()) View.GONE else View.VISIBLE

        binding.btnBack.setOnClickListener { finish() }

        setupImageGallery(images, title)
        setupCaptionToggle()

        if (url.isNotBlank()) {
            binding.btnAction.visibility = View.VISIBLE
            binding.btnAction.text = getString(if (isFile) R.string.btn_download else R.string.open_in_browser)
            binding.btnAction.setOnClickListener {
                if (isFile) {
                    ContentOpener.openFile(this, url, title)
                } else {
                    ContentOpener.open(this, url, title)
                }
            }
        }
    }

    /** با لمس نوار توضیحات (۱۰٪ پایین)، عکس جمع می‌شه و توضیحات کامل نشون داده می‌شه؛ لمس دوباره برمی‌گردونه. */
    private fun setupCaptionToggle() {
        binding.captionScroll.setOnClickListener { toggleCaption() }
    }

    private fun toggleCaption() {
        isCaptionExpanded = !isCaptionExpanded

        val imageWeight = if (isCaptionExpanded) IMAGE_WEIGHT_EXPANDED else IMAGE_WEIGHT_COLLAPSED
        val captionWeight = if (isCaptionExpanded) CAPTION_WEIGHT_EXPANDED else CAPTION_WEIGHT_COLLAPSED

        animateWeight(binding.imageContainer, imageWeight)
        animateWeight(binding.captionScroll, captionWeight)

        binding.txtDescription.maxLines = if (isCaptionExpanded) Integer.MAX_VALUE else 2
        binding.txtHeadline.maxLines = if (isCaptionExpanded) Integer.MAX_VALUE else 1
    }

    private fun animateWeight(view: View, toWeight: Float) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        val fromWeight = params.weight
        ValueAnimator.ofFloat(fromWeight, toWeight).apply {
            duration = WEIGHT_ANIM_DURATION
            addUpdateListener { anim ->
                params.weight = anim.animatedValue as Float
                view.layoutParams = params
            }
            start()
        }
    }

    private fun setupImageGallery(images: List<String>, title: String) {
        binding.recyclerImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerImages.adapter = GalleryImageAdapter(
            files = images,
            onImageClick = { imageUrl ->
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra(ImageViewerActivity.EXTRA_URL, imageUrl)
                intent.putExtra(ImageViewerActivity.EXTRA_TITLE, title)
                startActivity(intent)
            },
            onOpenFile = { fileUrl ->
                ContentOpener.open(this, fileUrl, title)
            }
        )
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
        binding.txtImageCounter.text =
            String.format(java.util.Locale.US, getString(R.string.image_counter_format), position + 1, total)
        binding.txtImageCounter.textDirection = android.view.View.TEXT_DIRECTION_LTR
        binding.txtImageCounter.textLocales = android.os.LocaleList(java.util.Locale.US)
        binding.txtImageCounter.fontFeatureSettings = "tnum"
    }
}
