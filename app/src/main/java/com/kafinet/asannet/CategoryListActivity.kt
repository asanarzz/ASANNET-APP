package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
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
    private lateinit var moviesIrAdapter: PosterCarouselAdapter
    private lateinit var seriesIrAdapter: PosterCarouselAdapter

    private var allItems: List<ContentItem> = emptyList()
    private var typeFilter: ContentType? = null
    private var currentQuery: String = ""
    private var categoryLabel: String = ""

    // آیتم‌هایی که همین الان تو لیست نمایش داده می‌شن (برای ساختن صف پخش موزیک)
    private var displayedItems: List<ContentItem> = emptyList()

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
        moviesIrAdapter = PosterCarouselAdapter(emptyList()) { item -> openVideoDetail(item) }
        seriesIrAdapter = PosterCarouselAdapter(emptyList()) { item -> openVideoDetail(item) }

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
            binding.recyclerMoviesIr.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerMoviesIr.adapter = moviesIrAdapter
            binding.recyclerSeriesIr.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerSeriesIr.adapter = seriesIrAdapter
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
        val seriesIr = videos.filter { it.section?.trim() == "سریال ایرانی" }
        val moviesIr = videos.filter { it.section?.trim() == "فیلم ایرانی" }
        val movies = videos.filter {
            val s = it.section?.trim()
            s != "سریال" && s != "سریال ایرانی" && s != "فیلم ایرانی"
        }

        moviesAdapter.updateItems(movies)
        seriesAdapter.updateItems(series)
        moviesIrAdapter.updateItems(moviesIr)
        seriesIrAdapter.updateItems(seriesIr)

        binding.sectionMovies.visibility = if (movies.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.sectionSeries.visibility = if (series.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.sectionMoviesIr.visibility = if (moviesIr.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.sectionSeriesIr.visibility = if (seriesIr.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
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
        // «سایر» همیشه آخرین پوشه نشون داده می‌شه؛ قبل از اون، پوشه‌هایی که از پنل
        // مدیریت پین شدن (حداقل یکی از اعضاشون pinned=true باشه) اول میان
        val nonMisc = grouped.keys.filter { it != SECTION_MISC }
        val pinnedKeys = nonMisc.filter { key -> grouped[key].orEmpty().any { it.pinned } }
        val unpinnedKeys = nonMisc.filter { key -> grouped[key].orEmpty().none { it.pinned } }
        val orderedKeys = pinnedKeys + unpinnedKeys + grouped.keys.filter { it == SECTION_MISC }

        var folders = orderedKeys.map { key ->
            val displayName = if (key == SECTION_MISC) getString(R.string.section_misc) else key
            val members = grouped[key].orEmpty()
            val lockedFolder = members.isNotEmpty() && members.all { it.locked && it.lockGroup != null }
            SectionFolder(key, displayName, members.size, lockedFolder, members.firstOrNull())
        }

        if (currentQuery.isNotBlank()) {
            val q = currentQuery.trim()
            folders = folders.filter { it.displayName.contains(q, ignoreCase = true) }
        }

        sectionAdapter.updateFolders(folders)
        binding.layoutEmpty.visibility = if (folders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openSection(folder: SectionFolder) {
        val probe = folder.probe
        if (folder.locked && probe != null) {
            // پوشه‌ی قفل‌شده: اول توکن (یا دسترسی قبلی) چک می‌شود
            LockManager.withAccess(this, probe) { enterSection(folder) }
        } else {
            enterSection(folder)
        }
    }

    private fun enterSection(folder: SectionFolder) {
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
        // موارد پین‌شده از پنل مدیریت بالای لیست می‌آیند (ترتیب نسبی بقیه دست‌نخورده می‌ماند)
        filtered = filtered.sortedByDescending { it.pinned }
        displayedItems = filtered
        adapter.updateItems(filtered)
        binding.layoutEmpty.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** با تپ روی پوستر فیلم/سریال، صفحه‌ی جزئیات (پوستر بزرگ + توضیحات + دکمه‌ی
     *  دانلود فیلم) باز می‌شه — به‌جای پخش مستقیم. */
    private fun openVideoDetail(item: ContentItem) {
        LockManager.withAccess(this, item) { openVideoDetailUnlocked(it) }
    }

    private fun openVideoDetailUnlocked(item: ContentItem) {
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
        LockManager.withAccess(this, item) { openItemUnlocked(it) }
    }

    private fun openItemUnlocked(item: ContentItem) {
        // برای دسته‌ی ویدیو و موزیک، تصویر (پوستر/کاور) فقط برای نمایش تو کارته؛
        // نباید بره تو گالری عکس فقط چون عکس کاور داره.
        if (item.images.isNotEmpty() && item.type != ContentType.VIDEO && item.type != ContentType.MUSIC) {
            val intent = Intent(this, GalleryDetailActivity::class.java)
            intent.putExtra(GalleryDetailActivity.EXTRA_TITLE, item.title)
            intent.putExtra(GalleryDetailActivity.EXTRA_DESCRIPTION, item.description)
            intent.putStringArrayListExtra(GalleryDetailActivity.EXTRA_IMAGES, ArrayList(item.images))
            intent.putExtra(GalleryDetailActivity.EXTRA_URL, item.url)
            intent.putExtra(GalleryDetailActivity.EXTRA_IS_FILE, item.type == ContentType.FILE)
            intent.putExtra(GalleryDetailActivity.EXTRA_ALLOW_EXTERNAL_BROWSER, item.allowExternalBrowser)
            intent.putExtra(GalleryDetailActivity.EXTRA_DESKTOP_MODE, item.desktopMode)
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

        // موزیک با اپ پخش موزیکِ نصب‌شده‌ی خودِ گوشی باز می‌شه (نه پخش‌کننده‌ی داخلی
        // ما) — اول دانلودش می‌کنیم (که مشکل نوع فایل اشتباهِ گیت‌هاب رو دور بزنیم)
        // و بعد با اپ مناسب گوشی بازش می‌کنیم.
        if (item.type == ContentType.MUSIC) {
            showMusicOptions(item)
            return
        }

        // برای همه‌ی بقیه‌ی دسته‌ها، بر اساس پسوند فایل تصمیم گرفته می‌شه که چطور باز بشه —
        // نه بر اساس این‌که تو کدوم دسته قرار داره
        ContentOpener.open(this, resolveUrl(item.url), item.title, item.allowExternalBrowser, item.desktopMode)
    }

    /**
     * با لمس یک آهنگ، سه انتخاب نشون داده می‌شه:
     *  ۱) پخش همه با پلیر خود برنامه (از همین آهنگ به بعد، پشت‌سرهم تا آخر لیست همین پوشه)
     *  ۲) فقط همین آهنگ با پلیر خود برنامه
     *  ۳) روش قبلی: دانلود و باز کردن با برنامه‌ی پخش موزیکِ دیگه‌ی گوشی
     */
    private fun showMusicOptions(item: ContentItem) {
        // اگر این مورد چند آهنگ (لینک با برچسب) دارد، اول لیست آهنگ‌هایش نشان داده می‌شود
        if (item.links.isNotEmpty()) {
            showMusicSongList(item)
            return
        }
        val labels = arrayOf(
            "پخش همه به ترتیب",
            "پخش فقط همین آهنگ",
            "پخش با پلیر بیرونی"
        )
        showButtonDialog(item.title, labels.toList()) { which ->
            when (which) {
                0 -> startInAppMusic(item, playAll = true)
                1 -> startInAppMusic(item, playAll = false)
                else -> DownloadHelper.downloadAndOpenExternally(
                    this, resolveUrl(item.url), item.title, "audio/*", showProgress = true
                )
            }
        }
    }

    /** پنجره‌ای که گزینه‌هایش دکمه‌های جدا (هرکدام در یک کادر) هستند. */
    private fun showButtonDialog(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        val sidePad = (20 * density).toInt()
        container.setPadding(sidePad, (12 * density).toInt(), sidePad, (8 * density).toInt())
        var dialog: androidx.appcompat.app.AlertDialog? = null
        labels.forEachIndexed { i, label ->
            val button = android.widget.Button(this)
            button.text = label
            button.isAllCaps = false
            button.textSize = 15f
            button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, R.color.primary)
                )
            )
            button.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.white))
            val params = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (50 * density).toInt()
            )
            if (i > 0) params.topMargin = (10 * density).toInt()
            button.setOnClickListener {
                dialog?.dismiss()
                onPick(i)
            }
            container.addView(button, params)
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .create()
        dialog.show()
    }

    /** آهنگ‌های یک مورد: آدرس خودِ مورد (اگر باشد) + همه‌ی لینک‌های برچسب‌دارش. */
    private fun musicSongsOf(item: ContentItem): List<Pair<String, String>> {
        val songs = mutableListOf<Pair<String, String>>()
        if (item.url.isNotBlank()) songs.add(item.title to resolveUrl(item.url))
        item.links.forEachIndexed { i, l ->
            songs.add(l.label.ifBlank { "${item.title} ${i + 1}" } to resolveUrl(l.url))
        }
        return songs
    }

    /** لیست آهنگ‌های یک مورد: «پخش همه» + هر آهنگ جدا. */
    private fun showMusicSongList(item: ContentItem) {
        val songs = musicSongsOf(item)
        val titles = songs.map { it.first }
        val urls = songs.map { it.second }
        val entries = (listOf("▶ پخش همه (${songs.size} آهنگ)") + titles).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(entries) { _, which ->
                if (which == 0) {
                    MusicPlayerActivity.launch(this, titles, urls, 0, true)
                } else {
                    showSongOptions(titles, urls, which - 1)
                }
            }
            .show()
    }

    private fun showSongOptions(titles: List<String>, urls: List<String>, index: Int) {
        val labels = arrayOf(
            "پخش همه به ترتیب",
            "پخش فقط همین آهنگ",
            "پخش با پلیر بیرونی"
        )
        showButtonDialog(titles[index], labels.toList()) { which ->
            when (which) {
                0 -> MusicPlayerActivity.launch(this, titles, urls, index, true)
                1 -> MusicPlayerActivity.launch(this, titles, urls, index, false)
                else -> DownloadHelper.downloadAndOpenExternally(
                    this, urls[index], titles[index], "audio/*", showProgress = true
                )
            }
        }
    }

    /** اسم نمایشی آهنگ در پخش‌کننده: «خواننده – اسم آهنگ» (اگر زیرعنوان کوتاه و متفاوت باشد). */
    private fun musicDisplayName(item: ContentItem): String {
        val song = item.description.trim()
        return if (song.isNotBlank() && song.length <= 60 && !song.contains('\n') && song != item.title) {
            item.title + " – " + song
        } else {
            item.title
        }
    }

    private fun startInAppMusic(item: ContentItem, playAll: Boolean) {
        // صف = همه‌ی آهنگ‌های همین لیست (پوشه) به ترتیب نمایش؛ آهنگ‌های قفل‌شده‌ی دیگه
        // (که لینک واقعی‌شون هنوز باز نشده) از صف کنار گذاشته می‌شن.
        val queue = displayedItems
            .filter { it.type == ContentType.MUSIC }
            .map { if (it.id == item.id) item else it }
            .filter { it.id == item.id || (!it.locked && it.url.isNotBlank()) }
        val startIndex = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val finalQueue = if (queue.isEmpty()) listOf(item) else queue
        MusicPlayerActivity.launch(
            this,
            finalQueue.map { musicDisplayName(it) },
            finalQueue.map { resolveUrl(it.url) },
            startIndex,
            playAll
        )
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
