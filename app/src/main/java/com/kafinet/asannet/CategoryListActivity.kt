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

    private var allItems: List<ContentItem> = emptyList()
    private var typeFilter: ContentType? = null
    private var currentQuery: String = ""
    private var categoryLabel: String = ""

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
        categoryLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        binding.txtTitle.text = categoryLabel
        lifecycleScope.launch { SupabaseClient.logVisit(this@CategoryListActivity, categoryLabel) }

        binding.btnBack.setOnClickListener { handleBackPress() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPress() }
        })

        adapter = ContentAdapter(emptyList()) { item -> openItem(item) }
        sectionAdapter = SectionFolderAdapter(emptyList()) { folder -> openSection(folder) }

        binding.recyclerContent.layoutManager = LinearLayoutManager(this)
        binding.recyclerContent.adapter = adapter

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
        if (hasSections && currentSection != null) {
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

    /** پسوندهایی که واقعاً باید دانلود بشن (فایل‌های باینری)؛ هر چیز دیگه‌ای —
     *  از جمله لینک‌های ساده و فایل‌های HTML — با مرورگر داخلی باز می‌شود. */
    private val DOWNLOAD_EXTENSIONS = setOf(
        "apk", "zip", "rar", "7z", "pdf", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "mp3", "wav", "ogg", "ipa"
    )

    private fun isDownloadableFile(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        val ext = clean.substringAfterLast('.', missingDelimiterValue = "")
        return ext in DOWNLOAD_EXTENSIONS
    }

    private fun openItem(item: ContentItem) {
        when (item.type) {
            ContentType.RADIO -> {
                val intent = Intent(this, RadioPlayerActivity::class.java)
                intent.putExtra(RadioPlayerActivity.EXTRA_URL, item.url)
                intent.putExtra(RadioPlayerActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.TEST, ContentType.LINK, ContentType.POLL, ContentType.FUN -> {
                val intent = Intent(this, WebViewActivity::class.java)
                intent.putExtra(WebViewActivity.EXTRA_URL, resolveUrl(item.url))
                intent.putExtra(WebViewActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.IMAGE, ContentType.BANNER -> {
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra(ImageViewerActivity.EXTRA_URL, item.url)
                intent.putExtra(ImageViewerActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.VIDEO -> {
                val intent = Intent(this, VideoPlayerActivity::class.java)
                intent.putExtra(VideoPlayerActivity.EXTRA_URL, item.url)
                intent.putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.MUSIC -> {
                if (DownloadHelper.ensureStoragePermission(this)) {
                    DownloadHelper.downloadUrl(this, item.url, item.title)
                }
            }
            ContentType.FILE, ContentType.SOFTWARE -> {
                if (isDownloadableFile(item.url)) {
                    if (DownloadHelper.ensureStoragePermission(this)) {
                        DownloadHelper.downloadUrl(this, item.url, item.title)
                    }
                } else {
                    // لینک ساده یا صفحه‌ی HTML — با مرورگر داخلی باز می‌شود؛
                    // دکمه‌ی دانلود هم بالای صفحه هست اگه کسی خواست خودِ فایل رو ذخیره کنه
                    val intent = Intent(this, WebViewActivity::class.java)
                    intent.putExtra(WebViewActivity.EXTRA_URL, resolveUrl(item.url))
                    intent.putExtra(WebViewActivity.EXTRA_TITLE, item.title)
                    intent.putExtra(WebViewActivity.EXTRA_ALLOW_DOWNLOAD, true)
                    startActivity(intent)
                }
            }
            ContentType.NEWSPAPER -> {
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra(ImageViewerActivity.EXTRA_URL, item.url)
                intent.putExtra(ImageViewerActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.POWER_OUTAGE, ContentType.PRICE, ContentType.SERVICES -> {
                val intent = Intent(this, WebViewActivity::class.java)
                intent.putExtra(WebViewActivity.EXTRA_URL, resolveUrl(item.url))
                intent.putExtra(WebViewActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.DOCS, ContentType.HOME_BANNER -> { /* این نوع‌ها در این لیست ظاهر نمی‌شوند */ }
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
