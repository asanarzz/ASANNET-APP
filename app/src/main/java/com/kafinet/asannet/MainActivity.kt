package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val categories = listOf(
            CategoryEntry(ContentType.IMAGE, getString(R.string.cat_image), R.drawable.ic_image, R.drawable.icon_circle_image),
            CategoryEntry(ContentType.VIDEO, getString(R.string.cat_video), R.drawable.ic_video, R.drawable.icon_circle_video),
            CategoryEntry(ContentType.BANNER, getString(R.string.cat_banner), R.drawable.ic_banner, R.drawable.icon_circle_banner),
            CategoryEntry(ContentType.LINK, getString(R.string.cat_link), R.drawable.ic_link, R.drawable.icon_circle_link),
            CategoryEntry(ContentType.FILE, getString(R.string.cat_file), R.drawable.ic_file, R.drawable.icon_circle_file),
            CategoryEntry(ContentType.TEST, getString(R.string.cat_test), R.drawable.ic_test, R.drawable.icon_circle_test),
            CategoryEntry(ContentType.POLL, getString(R.string.cat_poll), R.drawable.ic_poll, R.drawable.icon_circle_poll),
            CategoryEntry(ContentType.SOFTWARE, getString(R.string.cat_software), R.drawable.ic_software, R.drawable.icon_circle_software),
            CategoryEntry(ContentType.MUSIC, getString(R.string.cat_music), R.drawable.ic_music, R.drawable.icon_circle_music),
            CategoryEntry(ContentType.RADIO, getString(R.string.cat_radio), R.drawable.ic_radio, R.drawable.icon_circle_radio),
            CategoryEntry(ContentType.FUN, getString(R.string.cat_fun), R.drawable.ic_fun, R.drawable.icon_circle_fun),
            CategoryEntry(ContentType.POWER_OUTAGE, getString(R.string.cat_power), R.drawable.ic_power, R.drawable.icon_circle_power),
            CategoryEntry(ContentType.NEWSPAPER, getString(R.string.cat_newspaper), R.drawable.ic_newspaper, R.drawable.icon_circle_newspaper),
            CategoryEntry(ContentType.PRICE, getString(R.string.cat_price), R.drawable.ic_price, R.drawable.icon_circle_price),
            CategoryEntry(ContentType.SERVICES, getString(R.string.cat_services), R.drawable.ic_services, R.drawable.icon_circle_services),
            CategoryEntry(null, getString(R.string.cat_docs), R.drawable.ic_docs, R.drawable.icon_circle_docs, isSubmit = true)
        )

        val bannerAdapter = BannerCarouselAdapter(emptyList()) { banner -> openBanner(banner) }
        binding.recyclerBanners.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerBanners.adapter = bannerAdapter
        loadBanners(bannerAdapter)

        binding.recyclerCategories.layoutManager = GridLayoutManager(this, 4)
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
        lifecycleScope.launch {
            val result = ContentRepository.load(this@MainActivity)
            val banners = result.items.filter { it.type == ContentType.HOME_BANNER }
            adapter.updateItems(banners)
            binding.recyclerBanners.visibility = if (banners.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
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
