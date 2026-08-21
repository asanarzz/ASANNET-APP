package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityCategoryListBinding
import kotlinx.coroutines.launch

class CategoryListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_LABEL = "extra_label"
    }

    private lateinit var binding: ActivityCategoryListBinding
    private lateinit var adapter: ContentAdapter

    private var allItems: List<ContentItem> = emptyList()
    private var typeFilter: ContentType? = null
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val typeKey = intent.getStringExtra(EXTRA_TYPE)
        typeFilter = if (typeKey != null) ContentType.fromKey(typeKey) else null
        binding.txtTitle.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        binding.btnBack.setOnClickListener { finish() }

        adapter = ContentAdapter(emptyList()) { item -> openItem(item) }
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
        var filtered = allItems
        typeFilter?.let { type -> filtered = filtered.filter { it.type == type } }
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
            ContentType.TEST, ContentType.LINK, ContentType.POLL, ContentType.RADIO, ContentType.FUN -> {
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
            ContentType.FILE, ContentType.SOFTWARE, ContentType.MUSIC -> {
                if (DownloadHelper.ensureStoragePermission(this)) {
                    DownloadHelper.downloadUrl(this, item.url, item.title)
                }
            }
            ContentType.DOCS -> { /* این نوع در این لیست ظاهر نمی‌شود */ }
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
