package com.kafinet.asannet

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import com.kafinet.asannet.databinding.ActivityVideoDetailBinding

class VideoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_LINK_LABELS = "extra_link_labels"
        const val EXTRA_LINK_URLS = "extra_link_urls"
    }

    private lateinit var binding: ActivityVideoDetailBinding
    private lateinit var itemTitle: String
    private var itemId = ""
    private var myScore: Int? = null

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

        binding.txtHeaderTitle.text = forceEnglishDigits(itemTitle)
        binding.txtTitle.text = forceEnglishDigits(itemTitle)
        binding.txtDescription.text = forceEnglishDigits(description)
        binding.txtDescription.visibility =
            if (description.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        Glide.with(this)
            .load(posterUrl)
            .placeholder(R.drawable.ic_video)
            .into(binding.imgPoster)

        binding.btnBack.setOnClickListener { finish() }

        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        if (itemId.isNotBlank()) {
            binding.btnRate.visibility = View.VISIBLE
            binding.btnRate.setOnClickListener { showRatingDialog() }
            updateRatingUi()
            lifecycleScope.launch {
                RatingStore.refresh(this@VideoDetailActivity)
                myScore = RatingStore.myVote(this@VideoDetailActivity, itemId)
                updateRatingUi()
            }
        }

        if (linkUrls.isNotEmpty()) {
            for (i in linkUrls.indices) {
                val label = linkLabels.getOrNull(i).orEmpty()
                addDownloadButton(label, linkUrls[i])
            }
        } else if (videoUrl.isNotBlank()) {
            addDownloadButton("", videoUrl)
        }
    }

    /** میانگین امتیاز (★ 7.8) و متن دکمه‌ی امتیازدهی رو به‌روز می‌کنه. */
    private fun updateRatingUi() {
        val stat = RatingStore.get(itemId)
        if (stat != null) {
            binding.layoutRatingBadge.visibility = View.VISIBLE
            binding.txtRatingValue.text = forceEnglishDigits("★ ${stat.averageText()}")
            binding.txtRatingVotes.text = forceEnglishDigits(getString(R.string.rating_votes_format, stat.votes))
        } else {
            binding.layoutRatingBadge.visibility = View.GONE
        }
        val mine = myScore
        binding.btnRate.text = if (mine != null) {
            getString(R.string.rating_change_button, mine)
        } else {
            getString(R.string.rating_rate_button)
        }
    }

    /** پنجره‌ی انتخاب امتیاز: ده ستاره‌ی قابل‌لمس (۱ تا ۱۰). */
    private fun showRatingDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selected = myScore ?: 0
        val amber = Color.parseColor("#F59E0B")
        val gray = Color.parseColor("#CBD5E1")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@VideoDetailActivity, R.color.text_main))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        val starViews = ArrayList<TextView>()
        fun refreshStars() {
            for ((index, tv) in starViews.withIndex()) {
                tv.setTextColor(if (index < selected) amber else gray)
            }
            label.text = if (selected == 0) {
                getString(R.string.rating_choose)
            } else {
                getString(R.string.rating_selected, selected)
            }
        }

        for (score in 1..10) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val star = TextView(this).apply {
                text = "★"
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT
            }
            val number = TextView(this).apply {
                text = score.toString()
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT
                setTextColor(ContextCompat.getColor(this@VideoDetailActivity, R.color.text_muted))
            }
            cell.addView(star)
            cell.addView(number)
            cell.setOnClickListener {
                selected = score
                refreshStars()
            }
            starViews.add(star)
            row.addView(cell)
        }

        container.addView(label)
        container.addView(row)
        refreshStars()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rating_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.rating_submit) { _, _ ->
                if (selected in 1..10) {
                    submitRating(selected)
                } else {
                    Toast.makeText(this, R.string.rating_pick_first, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.rating_cancel, null)
            .show()
    }

    private fun submitRating(score: Int) {
        lifecycleScope.launch {
            val ok = RatingStore.submit(this@VideoDetailActivity, itemId, score)
            if (ok) {
                myScore = score
                RatingStore.refresh(this@VideoDetailActivity)
                updateRatingUi()
                Toast.makeText(this@VideoDetailActivity, R.string.rating_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VideoDetailActivity, R.string.rating_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addDownloadButton(label: String, url: String) {
        val density = resources.displayMetrics.density
        val button = Button(this)
        button.text = label.ifBlank { getString(R.string.btn_download) }
        button.textDirection = android.view.View.TEXT_DIRECTION_LTR
        button.textLocales = android.os.LocaleList(java.util.Locale.US)
        // فونت پیش‌فرض برنامه (yekan_boom) اعداد رو تو محیط راست‌به‌چپ خودکار به فارسی
        // تبدیل می‌کنه؛ برای این دکمه‌ها که باید عدد انگلیسی نشون بدن، فونت ساده‌ی
        // خودِ سیستم رو می‌ذاریم که این ویژگی رو نداره.
        button.typeface = android.graphics.Typeface.DEFAULT_BOLD
        button.fontFeatureSettings = "tnum"
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
                showDownloadChoice(resolveUrl(url), downloadTitle)
            }
        }

        binding.layoutDownloadLinks.addView(button, params)
    }

    /** دو انتخاب: دانلود همین الان، یا فقط اضافه‌کردن به لیست دانلود (بعداً از «دانلودها» شروعش می‌کنی). */
    private fun showDownloadChoice(url: String, downloadTitle: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(downloadTitle)
            .setItems(arrayOf("دانلود همین الان", "افزودن به لیست دانلود")) { _, which ->
                if (which == 0) {
                    DownloadHelper.downloadUrl(this, url, downloadTitle)
                } else {
                    DownloadHelper.addToDownloadList(this, url, downloadTitle)
                }
            }
            .show()
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
