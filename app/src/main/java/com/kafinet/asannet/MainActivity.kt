package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContentAdapter

    private var allItems: List<ContentItem> = emptyList()
    private var currentTypeFilter: ContentType? = null
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ContentAdapter(emptyList()) { item -> openItem(item) }
        binding.recyclerContent.layoutManager = LinearLayoutManager(this)
        binding.recyclerContent.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadContent() }
        binding.btnRefresh.setOnClickListener {
            binding.swipeRefresh.isRefreshing = true
            loadContent()
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentTypeFilter = when (checkedIds.firstOrNull()) {
                binding.chipTest.id -> ContentType.TEST
                binding.chipLink.id -> ContentType.LINK
                binding.chipImage.id -> ContentType.IMAGE
                binding.chipVideo.id -> ContentType.VIDEO
                binding.chipFile.id -> ContentType.FILE
                else -> null
            }
            applyFilters()
        }

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

    private fun loadContent() {
        lifecycleScope.launch {
            val result = ContentRepository.load(this@MainActivity)
            allItems = result.items
            applyFilters()
            binding.swipeRefresh.isRefreshing = false
            if (result.error) {
                Toast.makeText(this@MainActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilters() {
        var filtered = allItems
        currentTypeFilter?.let { type -> filtered = filtered.filter { it.type == type } }
        if (currentQuery.isNotBlank()) {
            val q = currentQuery.trim()
            filtered = filtered.filter {
                it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }
        adapter.updateItems(filtered)
        binding.layoutEmpty.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openItem(item: ContentItem) {
        when (item.type) {
            ContentType.TEST, ContentType.LINK -> {
                val intent = Intent(this, WebViewActivity::class.java)
                intent.putExtra(WebViewActivity.EXTRA_URL, resolveUrl(item.url))
                intent.putExtra(WebViewActivity.EXTRA_TITLE, item.title)
                startActivity(intent)
            }
            ContentType.IMAGE -> {
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
            ContentType.FILE -> {
                if (DownloadHelper.ensureStoragePermission(this)) {
                    DownloadHelper.downloadUrl(this, item.url, item.title)
                }
            }
        }
    }

    /** آدرس‌های محلی (فایل‌های داخل assets/tests) را به مسیر قابل بارگذاری در WebView تبدیل می‌کند. */
    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
