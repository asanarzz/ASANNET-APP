package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityCategoryListBinding
import kotlinx.coroutines.launch

class CategoryListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_LABEL = "extra_label"
        private const val SECTION_MISC = "__misc__"
    }

    private lateinit var binding: ActivityCategoryListBinding
    private lateinit var adapter: ContentAdapter
    private lateinit var sectionAdapter: SectionFolderAdapter
    private lateinit var moviesAdapter: PosterCarouselAdapter
    private lateinit var seriesAdapter: PosterCarouselAdapter

    private var allItems: List<ContentItem> = emptyList()
    private var typeFilter: ContentType? = null
    private var currentQuery: String = ""
    private var categoryLabel: String = ""

    // دسته‌ی «فیلم» به‌جای لیست معمولی، با دو ردیف افقی (فیلم/سریال) نمایش داده می‌شه
    private var isVideoGalleryMode: Boolean = false

    // اگه این دسته حداقل یک آیتم با «بخش» مشخص داشته باشه، حالت پوشه‌ای فعال می‌شه
    private var hasSections: Boolean = false
    // بخشی که الان توش هستیم؛ null یعنی تو لیست پوشه‌ها (یا حالت تخت قدیمی) هستیم
    private var currentSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val typeKey = intent.getStringExtra(EXTRA_TYPE)
        typeFilter = if (typeKey != null) ContentType.fromKey(typeKey) else null
        isVideoGalleryMode = typeFilter == ContentType.VIDEO
        categoryLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        binding.txtTitle.text = categoryLabel
        lifecycleScope.launch { SupabaseClient.logVisit(this@CategoryListActivity, categoryLabel) }

        binding.btnBack.setOnClickListener { handleBackPress() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPress() }
        })

        adapter = ContentAdapter(emptyList()) { item -> openItem(item) }
        sectionAdapter = SectionFolderAdapter(emptyList()) { folder -> openSection(folder) }
        moviesAdapter = PosterCarouselAdapter(emptyList()) { item -> openVideoDetail(item) }
        seriesAdapter = PosterCarouselAdapter(emptyList()) { item -> openVideoDetail(item) }

        binding.recyclerContent.layoutManager = LinearLayoutManager(this)
        binding.recyclerContent.adapter = adapter

        if (isVideoGalleryMode) {
            binding.layoutListMode.visibility = android.view.View.GONE
            binding.scrollVideoGallery.visibility = android.view.View.VISIBLE
            binding.recyclerMovies.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerMovies.adapter = moviesAdapter
            binding.recyclerSeries.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerSeries.adapter = seriesAdapter
        }

        binding.swipeRefresh.setOnRefreshListener { loadContent() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadContent()
    }

    private fun handleBackPress() {
        if (isVideoGalleryMode) {
            finish()
        } else if (hasSections && currentSection != null) {
            currentSection = null
            binding.txtTitle.text = categoryLabel
            binding.editSearch.text?.clear()
            applyFilters()
        } else {
            finish()
        }
    }

    private fun loadContent() {
        lifecycleScope.launch {
            val result = ContentRepository.load(this@CategoryListActivity)
            allItems = result.items
            applyFilters()
            binding.swipeRefresh.isRefreshing = false
            if (result.error) {
                Toast.makeText(this@CategoryListActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilters() {
        if (isVideoGalleryMode) {
            applyVideoFilters()
            return
        }

        var typeFiltered = allItems
        typeFilter?.let { type -> typeFiltered = typeFiltered.filter { it.type == type } }

        hasSections = typeFiltered.any { !it.section.isNullOrBlank() }

        if (hasSections && currentSection == null) {
            showFolderList(typeFiltered)
        } else {
            val scoped = if (hasSections) {
                typeFiltered.filter { (it.section ?: SECTION_MISC) == currentSection }
            } else {
                typeFiltered
            }
            showItemList(scoped)
        }
    }

    /** دسته‌ی «فیلم» رو به دو ردیف افقی فیلم و سریال تقسیم می‌کنه (بر اساس فیلد «بخش»؛
     *  section == "سریال" میره تو ردیف سریال، بقیه (فیلم یا خالی) میرن تو ردیف فیلم)،
     *  و جستجو رو رو عنوان/توضیحات هر دو ردیف همزمان اعمال می‌کنه. */
    private fun applyVideoFilters() {
        var videos = allItems.filter { it.type == ContentType.VIDEO }

        if (currentQuery.isNotBlank()) {
            val q = currentQuery.trim()
            videos = videos.filter {
                it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }

        val series = videos.filter { it.section?.trim() == "سریال" }
        val movies = videos.filter { it.section?.trim() != "سریال" }

        moviesAdapter.updateItems(movies)
        seriesAdapter.updateItems(series)

        binding.sectionMovies.visibility = if (movies.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.sectionSeries.visibility = if (series.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.layoutVideoEmpty.visibility = if (videos.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showFolderList(items: List<ContentItem>) {
        binding.recyclerContent.adapter = sectionAdapter
        binding.recyclerContent.layoutManager = LinearLayoutManager(this)

        val grouped = LinkedHashMap<String, MutableList<ContentItem>>()
        for (item in items) {
            val key = item.section?.takeIf { it.isNotBlank() } ?: SECTION_MISC
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }
        // «سایر» همیشه آخرین پوشه نشون داده می‌شه
        val orderedKeys = grouped.keys.filter { it != SECTION_MISC } + grouped.keys.filter { it == SECTION_MISC }

        var folders = orderedKeys.map { key ->
            val displayName = if (key == SECTION_MISC) getString(R.string.section_misc) else key
            SectionFolder(key, displayName, grouped[key]?.size ?: 0)
        }

        if (currentQuery.isNotBlank()) {
            val q = currentQuery.trim()
            folders = folders.filter { it.displayName.contains(q, ignoreCase = true) }
        }

        sectionAdapter.updateFolders(folders)
        binding.layoutEmpty.visibility = if (folders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openSection(folder: SectionFolder) {
        currentSection = folder.name
        binding.txtTitle.text = folder.displayName
        binding.editSearch.text?.clear()
        applyFilters()
    }

    private fun showItemList(items: List<ContentItem>) {
        binding.recyclerContent.adapter = adapter
        binding.recyclerContent.layoutManager = LinearLayoutManager(this)

        var filtered = items
        if (currentQuery.isNotBlank()) {
            val q = currentQuery.trim()
            filtered = filtered.filter {
                it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }
        adapter.updateItems(filtered)
        binding.layoutEmpty.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** با تپ روی پوستر فیلم/سریال، صفحه‌ی جزئیات (پوستر بزرگ + توضیحات + دکمه‌ی
     *  دانلود فیلم) باز می‌شه — به‌جای پخش مستقیم. */
    private fun openVideoDetail(item: ContentItem) {
        val posterUrl = item.images.firstOrNull().orEmpty()
        val intent = Intent(this, VideoDetailActivity::class.java)
        intent.putExtra(VideoDetailActivity.EXTRA_TITLE, item.title)
        intent.putExtra(VideoDetailActivity.EXTRA_DESCRIPTION, item.description)
        intent.putExtra(VideoDetailActivity.EXTRA_POSTER_URL, posterUrl)
        intent.putExtra(VideoDetailActivity.EXTRA_VIDEO_URL, item.url)
        intent.putStringArrayListExtra(
            VideoDetailActivity.EXTRA_LINK_LABELS,
            ArrayList(item.links.map { it.label })
        )
        intent.putStringArrayListExtra(
            VideoDetailActivity.EXTRA_LINK_URLS,
            ArrayList(item.links.map { it.url })
        )
        startActivity(intent)
    }

    private fun openItem(item: ContentItem) {
        // برای دسته‌ی ویدیو، تصویر (پوستر) فقط برای نمایش تو کارته؛ با تپ کردن باید
        // ویدیو پخش بشه، نه اینکه چون عکس پوستر داره ببرتش تو گالری عکس.
        if (item.images.isNotEmpty() && item.type != ContentType.VIDEO) {
            val intent = Intent(this, GalleryDetailActivity::class.java)
            intent.putExtra(GalleryDetailActivity.EXTRA_TITLE, item.title)
            intent.putExtra(GalleryDetailActivity.EXTRA_DESCRIPTION, item.description)
            intent.putStringArrayListExtra(GalleryDetailActivity.EXTRA_IMAGES, ArrayList(item.images))
            intent.putExtra(GalleryDetailActivity.EXTRA_URL, item.url)
            intent.putExtra(GalleryDetailActivity.EXTRA_IS_FILE, item.type == ContentType.FILE)
            startActivity(intent)
            return
        }

        // رادیو همیشه از پخش‌کننده‌ی پس‌زمینه‌ای استفاده می‌کنه، چون معمولاً یه استریم
        // پیوسته‌ست و ممکنه لینکش پسوند مشخصی نداشته باشه که تشخیص خودکار بشناسدش
        if (item.type == ContentType.RADIO) {
            val intent = Intent(this, RadioPlayerActivity::class.java)
            intent.putExtra(RadioPlayerActivity.EXTRA_URL, resolveUrl(item.url))
            intent.putExtra(RadioPlayerActivity.EXTRA_TITLE, item.title)
            startActivity(intent)
            return
        }

        // برای همه‌ی بقیه‌ی دسته‌ها، بر اساس پسوند فایل تصمیم گرفته می‌شه که چطور باز بشه —
        // نه بر اساس این‌که تو کدوم دسته قرار داره
        ContentOpener.open(this, resolveUrl(item.url), item.title)
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
