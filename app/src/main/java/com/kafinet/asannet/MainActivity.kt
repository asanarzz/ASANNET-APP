package com.kafinet.asannet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private var pendingUpdate: AppUpdateInfo? = null
    private var forcedUpdateDialog: android.app.AlertDialog? = null

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
        lifecycleScope.launch { SupabaseClient.logVisit(this@MainActivity, null) }


        val categories = listOf(
            // ردیف اول (از چپ به راست): موزیک، عکس، فیلم، تلویزیون، رادیو
            CategoryEntry(ContentType.MUSIC, "موزیک", R.drawable.cat_music, 0),
            CategoryEntry(ContentType.IMAGE, getString(R.string.cat_image), R.drawable.cat_image, 0),
            CategoryEntry(ContentType.VIDEO, getString(R.string.cat_video), R.drawable.cat_video, 0),
            CategoryEntry(ContentType.TV, "تلویزیون", R.drawable.cat_tv, 0),
            CategoryEntry(ContentType.RADIO, getString(R.string.cat_radio), R.drawable.cat_radio, 0),
            // ردیف دوم: سرگرمی، بنر، فایل، لینک، نرم‌افزار
            CategoryEntry(ContentType.FUN, getString(R.string.cat_fun), R.drawable.cat_fun, 0),
            CategoryEntry(ContentType.BANNER, getString(R.string.cat_banner), R.drawable.cat_banner, 0),
            CategoryEntry(ContentType.FILE, getString(R.string.cat_file), R.drawable.cat_file, 0),
            CategoryEntry(ContentType.LINK, getString(R.string.cat_link), R.drawable.cat_link, 0),
            CategoryEntry(ContentType.SOFTWARE, "نرم افزار", R.drawable.cat_software, 0),
            // ردیف سوم: خاموشی برق، روزنامه، دلار و طلا، نظرسنجی، آزمون
            CategoryEntry(ContentType.POWER_OUTAGE, getString(R.string.cat_power), R.drawable.cat_power, 0),
            CategoryEntry(ContentType.NEWSPAPER, "روزنامه", R.drawable.cat_newspaper, 0),
            CategoryEntry(ContentType.PRICE, "دلار و طلا", R.drawable.cat_price, 0),
            CategoryEntry(ContentType.POLL, getString(R.string.cat_poll), R.drawable.cat_poll, 0),
            CategoryEntry(ContentType.TEST, getString(R.string.cat_test), R.drawable.cat_test, 0),
            // ردیف چهارم: ارسال مدارک، همه خدمات
            CategoryEntry(null, getString(R.string.cat_docs), R.drawable.cat_docs, 0, isSubmit = true),
            CategoryEntry(ContentType.SERVICES, "همه خدمات", R.drawable.cat_services, 0),
            // ردیف پنجم: دستیار آسان‌نت (ربات محلی داخل برنامه)
            CategoryEntry(null, "دستیار", R.drawable.cat_assistant, 0)
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
            } else if (entry.type == ContentType.BANNER) {
                startActivity(Intent(this, AdsActivity::class.java))
            } else if (entry.type == null) {
                // دستیار آسان‌نت: صفحهٔ محلی داخل assets (بدون اینترنت)
                startActivity(Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_URL, "file:///android_asset/assistant/assistant.html")
                    putExtra(WebViewActivity.EXTRA_TITLE, "دستیار آسان‌نت")
                    putExtra(WebViewActivity.EXTRA_ALLOW_EXTERNAL_BROWSER, false)
                    putExtra(WebViewActivity.EXTRA_DESKTOP_MODE, false)
                })
            } else {
                val intent = Intent(this, CategoryListActivity::class.java)
                intent.putExtra(CategoryListActivity.EXTRA_TYPE, entry.type?.key)
                intent.putExtra(CategoryListActivity.EXTRA_LABEL, entry.label)
                startActivity(intent)
            }
        }

        updateHeaderDate()

        binding.txtAppVersion.text = getString(R.string.app_version_label, BuildConfig.VERSION_NAME)
        binding.txtDrawerNationalCode.text =
            SessionManager.getFullName(this) ?: getString(R.string.drawer_username_fallback)

        binding.swipeRefresh.setOnRefreshListener { loadBanners(bannerAdapter) }

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        setupVpnButton()

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
        binding.navItemDownloads.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        binding.navItemSupport.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            startActivity(Intent(this, SupportActivity::class.java))
        }
        binding.navItemUpdate.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            val update = pendingUpdate
            if (update != null) {
                Toast.makeText(this, "در حال دانلود نسخه‌ی ${update.versionName}…", Toast.LENGTH_SHORT).show()
                DownloadHelper.downloadAndInstallApk(this, update.apkUrl, "ASANNET-${update.versionName}")
            } else {
                Toast.makeText(this, "برنامه به‌روزه", Toast.LENGTH_SHORT).show()
            }
        }
        checkForAppUpdate()
        checkForUnreadSupportReply()

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
        updateHeaderDate()
        updateVpnStatusIcon()
        bannerAutoScrollHandler.removeCallbacks(bannerAutoScrollRunnable)
        bannerAutoScrollHandler.postDelayed(bannerAutoScrollRunnable, 3000)
    }

    // رنگ‌های وضعیت دکمه‌ی فیلترشکن: خاکستری = قطع، سبز = یه وی‌پی‌ان سیستمی الان فعاله
    private val vpnColorDisconnected = Color.parseColor("#B8B5E8")
    private val vpnColorConnected = Color.parseColor("#22C55E")

    /** دکمه‌ی فیلترشکن: اپ‌های فیلترشکن نصب‌شده رو (هر کدوم، بدون نیاز به دونستن
     *  اسم‌شون از قبل) پیدا می‌کنه و بازشون می‌کنه. رنگ دکمه هم نشون می‌ده آیا الان
     *  یه وی‌پی‌ان سیستمی (مال هر اپی) فعاله یا نه — چون وقتی کاربر از یکی از این
     *  اپ‌ها وصل بشه، کل ترافیک گوشی از جمله وب‌ویوی خودِ آسان‌نت هم از همون تونل رد می‌شه. */
    private fun setupVpnButton() {
        updateVpnStatusIcon()
        binding.btnVpn.setOnClickListener {
            val vpnApps = findInstalledVpnApps()
            when {
                vpnApps.isEmpty() -> {
                    Toast.makeText(
                        this,
                        "هیچ اپ فیلترشکنی روی گوشیت پیدا نشد. یکی مثل HotVPN یا Psiphon نصب کن.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                vpnApps.size == 1 -> launchVpnApp(vpnApps[0].packageName)
                else -> showVpnAppPicker(vpnApps)
            }
        }
    }

    /** لیست پکیج‌نیم اپ‌های نصب‌شده‌ای که یه VpnService سیستمی اعلام کردن (یعنی
     *  جزو اپ‌های فیلترشکن/وی‌پی‌ان هستن) — بدون نیاز به لیست دستی از اسم اپ‌ها. */
    private fun findInstalledVpnApps(): List<android.content.pm.ApplicationInfo> {
        val pm = packageManager
        val intent = Intent("android.net.VpnService")
        val services = try {
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            emptyList()
        }
        return services.mapNotNull { resolveInfo ->
            try {
                pm.getApplicationInfo(resolveInfo.serviceInfo.packageName, 0)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.packageName }
    }

    private fun launchVpnApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "این اپ قابل باز شدن نیست.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVpnAppPicker(apps: List<android.content.pm.ApplicationInfo>) {
        val pm = packageManager
        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("کدوم فیلترشکن؟")
            .setItems(labels) { _, which -> launchVpnApp(apps[which].packageName) }
            .show()
    }

    /** رنگ دکمه رو بر اساس اینکه الان یه وی‌پی‌ان سیستمی (هر کدوم) فعاله یا نه، ست می‌کنه. */
    private fun updateVpnStatusIcon() {
        val connected = isSystemVpnActive()
        binding.btnVpn.setColorFilter(if (connected) vpnColorConnected else vpnColorDisconnected)
    }

    private fun isSystemVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** تاریخ شمسی و میلادی امروز را در هدر می‌نویسد. چون هر بار که صفحه‌ی اصلی
     *  دوباره باز می‌شود (onResume) این تابع صدا زده می‌شود، همیشه تاریخِ «همان روزی
     *  که کاربر اپ را باز کرده» نمایش داده می‌شود و نیازی به تایمر جداگانه نیست. */
    private fun updateHeaderDate() {
        val (gy, gm, gd) = PersianDateUtils.todayGregorian().let { Triple(it[0], it[1], it[2]) }
        val (jy, jm, jd) = PersianDateUtils.gregorianToJalali(gy, gm, gd).let { Triple(it[0], it[1], it[2]) }
        binding.txtHeaderDateJalali.text = PersianDateUtils.formatDate(jy, jm, jd)
        binding.txtHeaderDateGregorian.text = PersianDateUtils.formatDate(gy, gm, gd)
        binding.txtHeaderWeekday.text = PersianDateUtils.todayWeekDayName()
    }

    /** چک می‌کنه آیا نسخه‌ی جدیدتری از اپ منتشر شده یا نه (از فایل version.json که
     *  خودِ بیلد گیت‌هاب بعد از هر بیلد موفق می‌سازه)؛ اگه بود، رو آیتم «به‌روزرسانی
     *  برنامه» تو منو یه نقطه‌ی سبز نشون میده. */
    private fun checkForAppUpdate() {
        lifecycleScope.launch {
            val update = AppUpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE)
            pendingUpdate = update
            binding.dotUpdateAvailable.visibility =
                if (update != null) android.view.View.VISIBLE else android.view.View.GONE
            if (update != null && update.force) showForcedUpdateDialog(update)
        }
    }

    /** پنجره‌ی به‌روزرسانی اجباری: بسته نمی‌شود و تا به‌روزرسانی نکنید نمی‌شود از برنامه استفاده کرد. */
    private fun showForcedUpdateDialog(update: AppUpdateInfo) {
        if (isFinishing || isDestroyed) return
        if (forcedUpdateDialog?.isShowing == true) return
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("به‌روزرسانی ضروری")
            .setMessage(
                "نسخه‌ی جدید «آسان نت» (" + update.versionName + ") منتشر شده است.\n" +
                    "برای ادامه‌ی استفاده از برنامه، لطفاً آن را به‌روزرسانی کنید."
            )
            .setCancelable(false)
            .setPositiveButton("به‌روزرسانی", null)
            .setNeutralButton("دانلود با مرورگر", null)
            .setNegativeButton("خروج") { _, _ -> finishAffinity() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        // با دادن listener بعد از show، دکمه‌ها پنجره را نمی‌بندند.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            DownloadHelper.downloadAndInstallApk(this, update.apkUrl, "ASANNET-" + update.versionName)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(update.apkUrl)))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
        forcedUpdateDialog = dialog
    }

    override fun onDestroy() {
        try {
            forcedUpdateDialog?.dismiss()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    /** اگه پشتیبانی جواب تازه‌ای داده که کاربر هنوز ندیده، رو آیتم «پیام به
     *  پشتیبانی» تو منو یه نقطه‌ی سبز نشون میده. */
    private fun checkForUnreadSupportReply() {
        val nationalCode = SessionManager.getNationalCode(this) ?: return
        lifecycleScope.launch {
            val messages = SupabaseClient.fetchSupportMessages(this@MainActivity, nationalCode)
            val lastAdmin = messages.lastOrNull { it.sender == "admin" } ?: return@launch
            val lastSeen = getSharedPreferences("kafinet_support", MODE_PRIVATE)
                .getString("last_seen_reply_at", "")
            binding.dotSupportUnread.visibility =
                if (lastAdmin.createdAt != lastSeen) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        bannerAutoScrollHandler.removeCallbacks(bannerAutoScrollRunnable)
    }

    private fun openBanner(banner: ContentItem) {
        val intent = Intent(this, BannerDetailActivity::class.java)
        intent.putExtra(BannerDetailActivity.EXTRA_IMAGE_URL, banner.images.firstOrNull() ?: banner.url)
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
