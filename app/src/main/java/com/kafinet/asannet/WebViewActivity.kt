package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityWebviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ALLOW_DOWNLOAD = "extra_allow_download"

        // یوزرایجنت یه مرورگر معمولی دسکتاپ — بعضی سرورها (مثل رادیوهای اینترنتی) درخواست‌های
        // UA پیش‌فرض وب‌ویو اندروید رو رد می‌کنن یا کانکشن رو ری‌ست می‌کنن؛ با این UA اون مشکل حل می‌شه.
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityWebviewBinding
    private var currentUrl: String = ""
    private var didAutoRetry: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.txtToolbarTitle.text = title

        val allowDownload = intent.getBooleanExtra(EXTRA_ALLOW_DOWNLOAD, false)
        if (allowDownload) {
            binding.btnDownload.visibility = View.VISIBLE
            binding.btnDownload.setOnClickListener {
                if (DownloadHelper.ensureStoragePermission(this)) {
                    DownloadHelper.downloadUrl(this, currentUrl, title)
                }
            }
        }

        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }
        binding.btnOpenBrowser.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
            } catch (e: Exception) {
                // ignore
            }
        }
        binding.btnRetry.setOnClickListener { reload() }

        setupWebView()
        if (currentUrl.isNotBlank()) {
            loadSmart(currentUrl)
        }
    }

    /**
     * گیت‌هاب فایل‌های خام (raw.githubusercontent.com) را همیشه با نوع «متن ساده»
     * می‌فرستد، حتی اگر پسوندشان .html باشد — در نتیجه وب‌ویو به‌جای رندر کردن
     * صفحه، خودِ کدش را مثل متن نشان می‌دهد. برای فایل‌های .html/.htm، محتوا را
     * دستی می‌گیریم و با نوع درست (text/html) به وب‌ویو می‌دهیم.
     */
    private fun loadSmart(url: String) {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        if (clean.endsWith(".html") || clean.endsWith(".htm")) {
            lifecycleScope.launch {
                val html = withContext(Dispatchers.IO) { fetchTextOrNull(url) }
                if (html != null) {
                    val baseUrl = url.substringBeforeLast('/') + "/"
                    binding.webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                } else {
                    binding.webView.loadUrl(url)
                }
            }
        } else {
            binding.webView.loadUrl(url)
        }
    }

    private fun fetchTextOrNull(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = DESKTOP_USER_AGENT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showError(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                didAutoRetry = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // فقط برای بارگذاری صفحه‌ی اصلی خطا رو نشون بده، نه برای زیرمنابع (عکس، فونت و...)
                if (request?.isForMainFrame == true) {
                    handleLoadFailure()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
    }

    /**
     * خیلی از خطاهای «net::ERR_CONNECTION_RESET» موقتی و لحظه‌ای هستن (مثلاً یه ری‌ست کوتاه
     * تو شبکه). برای همین یه‌بار خودکار و بی‌سروصدا بعد از یک‌ونیم ثانیه دوباره تلاش می‌کنیم؛
     * اگه بازم شکست خورد، به کاربر پیام و دکمه‌ی تلاش دوباره نشون می‌دیم.
     */
    private fun handleLoadFailure() {
        if (!didAutoRetry) {
            didAutoRetry = true
            Handler(Looper.getMainLooper()).postDelayed({ reload() }, 1500)
        } else {
            showError(true)
        }
    }

    private fun reload() {
        showError(false)
        loadSmart(currentUrl)
    }

    private fun showError(visible: Boolean) {
        binding.layoutWebError.visibility = if (visible) View.VISIBLE else View.GONE
        binding.webView.visibility = if (visible) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
