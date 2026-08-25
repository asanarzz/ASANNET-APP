package com.kafinet.asannet

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.kafinet.asannet.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bannerAdapter: BannerCarouselAdapter

    private val bannerAutoScrollHandler = Handler(Looper.getMainLooper())
    private var bannerAutoScrollIndex = 0
    private val bannerAutoScrollRunnable = object : Runnable {
        override fun run() {
            val count = bannerAdapter.itemCount
            if (count > 1) {
                bannerAutoScrollIndex = (bannerAutoScrollIndex + 1) % count
                binding.recyclerBanners.smoothScrollToPosition(bannerAutoScrollIndex)
            }
            bannerAutoScrollHandler.postDelayed(this, 3000)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* اگر کاربر رد کند، اپ عادی کار می‌کند؛ فقط نوتیفیکیشن نمی‌بیند */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ensureNotificationPermission()


        val categories = listOf(
            CategoryEntry(ContentType.IMAGE, getString(R.string.cat_image), R.drawable.ic_photo_image, 0),
            CategoryEntry(ContentType.VIDEO, getString(R.string.cat_video), R.drawable.ic_photo_video, 0),
            CategoryEntry(ContentType.BANNER, getString(R.string.cat_banner), R.drawable.ic_photo_banner, 0),
            CategoryEntry(ContentType.LINK, getString(R.string.cat_link), R.drawable.ic_photo_link, 0),
            CategoryEntry(ContentType.FILE, getString(R.string.cat_file), R.drawable.ic_photo_file, 0),
            CategoryEntry(ContentType.TEST, getString(R.string.cat_test), R.drawable.ic_photo_test, 0),
            CategoryEntry(ContentType.POLL, getString(R.string.cat_poll), R.drawable.ic_photo_poll, 0),
            CategoryEntry(ContentType.SOFTWARE, getString(R.string.cat_software), R.drawable.ic_photo_software, 0),
            CategoryEntry(ContentType.MUSIC, getString(R.string.cat_music), R.drawable.ic_photo_music, 0),
            CategoryEntry(ContentType.RADIO, getString(R.string.cat_radio), R.drawable.ic_photo_radio, 0),
            CategoryEntry(ContentType.FUN, getString(R.string.cat_fun), R.drawable.ic_photo_fun, 0),
            CategoryEntry(ContentType.POWER_OUTAGE, getString(R.string.cat_power), R.drawable.ic_photo_power, 0),
            CategoryEntry(ContentType.NEWSPAPER, getString(R.string.cat_newspaper), R.drawable.ic_photo_newspaper, 0),
            CategoryEntry(ContentType.PRICE, getString(R.string.cat_price), R.drawable.ic_photo_price, 0),
            CategoryEntry(ContentType.SERVICES, getString(R.string.cat_services), R.drawable.ic_photo_services, 0),
            CategoryEntry(null, getString(R.string.cat_docs), R.drawable.ic_photo_docs, 0, isSubmit = true)
        )

        val bannerAdapter = BannerCarouselAdapter(emptyList()) { banner -> openBanner(banner) }
        this.bannerAdapter = bannerAdapter
        binding.recyclerBanners.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerBanners.adapter = bannerAdapter
        PagerSnapHelper().attachToRecyclerView(binding.recyclerBanners)
        loadBanners(bannerAdapter)

        binding.recyclerCategories.layoutManager = GridLayoutManager(this, 5)
        binding.recyclerCategories.adapter = CategoryGridAdapter(categories) { entry ->
            if (entry.isSubmit) {
                startActivity(Intent(this, SubmitDocumentsActivity::class.java))
            } else {
                val intent = Intent(this, CategoryListActivity::class.java)
                intent.putExtra(CategoryListActivity.EXTRA_TYPE, entry.type?.key)
                intent.putExtra(CategoryListActivity.EXTRA_LABEL, entry.label)
                startActivity(intent)
            }
        }

        binding.txtAppVersion.text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)
        binding.txtDrawerNationalCode.text =
            SessionManager.getFullName(this) ?: getString(R.string.drawer_username_fallback)

        binding.swipeRefresh.setOnRefreshListener { loadBanners(bannerAdapter) }

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navItemSettings.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.navItemAbout.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.navItemContact.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, ContactActivity::class.java))
        }
        binding.navItemShareApk.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            shareApk()
        }
        binding.navItemLogout.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            SessionManager.logout(this)
            val intent = Intent(this, RegistrationActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    /** بنرهای تبلیغاتی بالای صفحه‌ی اصلی را از همان منبع محتوای اصلی (نوع home_banner) می‌خواند. */
    private fun loadBanners(adapter: BannerCarouselAdapter) {
        // عرض هر بنر رو ۹۵٪ عرض صفحه می‌کنیم (نه تمام‌عرض) تا لبه‌ی بنر بعدی هم کمی
        // از گوشه‌ی صفحه دیده بشه — همین یه تکه پیدا بودن، به کاربر می‌فهمونه که
        // می‌تونه ورق بزنه و بنرهای بیشتری هم هست.
        val screenWidthPx = resources.displayMetrics.widthPixels
        adapter.setItemWidth((screenWidthPx * 0.95).toInt())

        lifecycleScope.launch {
            val result = ContentRepository.load(this@MainActivity)
            // آیتم‌های تازه‌تر به انتهای فهرست اضافه می‌شوند؛ برعکسش می‌کنیم تا
            // آخرین بنر آپلودشده همیشه اول (و اولین چیزی که کاربر می‌بیند) باشد.
            val banners = result.items.filter { it.type == ContentType.HOME_BANNER }.reversed()
            adapter.updateItems(banners)
            binding.recyclerBanners.visibility = if (banners.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            binding.swipeRefresh.isRefreshing = false
            bannerAutoScrollIndex = 0
            binding.recyclerBanners.scrollToPosition(0)
        }
    }

    override fun onResume() {
        super.onResume()
        bannerAutoScrollHandler.removeCallbacks(bannerAutoScrollRunnable)
        bannerAutoScrollHandler.postDelayed(bannerAutoScrollRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        bannerAutoScrollHandler.removeCallbacks(bannerAutoScrollRunnable)
    }

    private fun openBanner(banner: ContentItem) {
        val intent = Intent(this, BannerDetailActivity::class.java)
        intent.putExtra(BannerDetailActivity.EXTRA_IMAGE_URL, banner.url)
        intent.putExtra(BannerDetailActivity.EXTRA_TITLE, banner.title)
        intent.putExtra(BannerDetailActivity.EXTRA_DESCRIPTION, banner.description)
        startActivity(intent)
    }

    /** فایل نصب (APK) فعلی برنامه را در حافظه‌ی موقت کپی و از طریق FileProvider به اشتراک می‌گذارد. */
    private fun shareApk() {
        lifecycleScope.launch {
            try {
                val destUri = withContext(Dispatchers.IO) {
                    val src = File(applicationInfo.sourceDir)
                    val destDir = File(cacheDir, "share")
                    destDir.mkdirs()
                    val dest = File(destDir, "AsanNet.apk")
                    src.copyTo(dest, overwrite = true)
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", dest)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, destUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_apk_chooser_title)))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.share_apk_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
