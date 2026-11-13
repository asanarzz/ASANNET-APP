package com.kafinet.asannet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityWebviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WebViewActivity : AppCompatActivity() {

    companion object {
        // Web Share API در وب‌ویو وجود ندارد. این اسکریپت navigator.share / navigator.canShare را
        // به پنجره‌ی اشتراک‌گذاری اندروید وصل می‌کند تا صفحه‌های HTML بدون تغییر هم کار کنند.
        private const val SHARE_POLYFILL_JS = """
(function () {
  if (window.__shareBridge || !window.AndroidDownloader || !AndroidDownloader.shareFile) return;
  window.__shareBridge = 1;
  function toDataUri(f) {
    return new Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
      r.readAsDataURL(f);
    });
  }
  navigator.canShare = function (d) {
    return !!d && (!!(d.files && d.files.length) || !!(d.text || d.url || d.title));
  };
  navigator.share = function (d) {
    d = d || {};
    if (d.files && d.files.length) {
      var f = d.files[0];
      return toDataUri(f).then(function (u) { AndroidDownloader.shareFile(u, f.name || 'file'); });
    }
    var t = [d.title, d.text, d.url].filter(Boolean).join('\n');
    if (!t) return Promise.reject(new TypeError('Nothing to share'));
    AndroidDownloader.shareText(t);
    return Promise.resolve();
  };
})();
"""

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ALLOW_DOWNLOAD = "extra_allow_download"
        const val EXTRA_ALLOW_EXTERNAL_BROWSER = "extra_allow_external_browser"
        const val EXTRA_DESKTOP_MODE = "extra_desktop_mode"
        /** true: رو هر عکسِ صفحه (بنرها) یه دکمه‌ی دانلودِ جدا نشون داده می‌شه — از پنل مدیریت برای هر مورد روشن/خاموش می‌شه */
        const val EXTRA_ALLOW_IMAGE_DOWNLOAD = "extra_allow_image_download"

        // رو هر عکس (و تو صفحه‌ی آب‌وهوا رو هر بنر) یه دکمه‌ی دانلودِ شناور می‌ذاره؛ فقط همون عکس ذخیره می‌شه.
        // تو آب‌وهوا اگه عکسِ بنر هنوز ساخته نشده، اول ساخته می‌شه و بعد ذخیره می‌شه.
        private const val IMAGE_DOWNLOAD_JS = """
(function () {
  if (window.__asImgDl) return;
  window.__asImgDl = 1;
  var SEC = (location.pathname.split('/')[2] || 'banner').toLowerCase();
  var STAMP = Math.floor(Date.now() / 1000);

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function toDataUri(src) {
    return fetch(src).then(function (r) { return r.blob(); }).then(function (b) {
      return new Promise(function (res) {
        var fr = new FileReader();
        fr.onloadend = function () { res(fr.result); };
        fr.readAsDataURL(b);
      });
    });
  }
  function bigImages() {
    return Array.prototype.slice.call(document.images).filter(function (i) {
      return i.complete && i.naturalWidth >= 300 && !i.closest('.res');
    });
  }
  function targets() {
    var list = bigImages().map(function (i) { return { el: i, kind: 'img' }; });
    Array.prototype.slice.call(document.querySelectorAll('.bn')).forEach(function (b) { list.push({ el: b, kind: 'bn' }); });
    list.sort(function (a, b) {
      return (a.el.compareDocumentPosition(b.el) & 4) ? -1 : 1;
    });
    return list;
  }
  async function saveTarget(t, n) {
    var img = null;
    if (t.kind === 'img') {
      img = t.el;
    } else {
      var box = t.el.parentNode;
      img = box.querySelector('.res img');
      if (!img) {
        var b = box.querySelector('.out button');
        if (b && !b.disabled) b.click();
        for (var k = 0; k < 120 && !img; k++) {
          await sleep(250);
          var c = box.querySelector('.res img');
          if (c && c.complete && c.naturalWidth > 0) img = c;
        }
      }
    }
    if (!img) return;
    var uri = await toDataUri(img.currentSrc || img.src);
    AndroidDownloader.onBlobReady(uri, 'asannet-' + SEC + '-' + STAMP + '-' + n);
  }
  // فلش بالای صفحه این تابع رو صدا می‌زنه: عکسی که الان بیشتر از همه تو صفحه دیده می‌شه رو دانلود می‌کنه.
  // (بنرِ آب‌وهوا اگه هنوز ساخته نشده، اول ساخته و بعد ذخیره می‌شه)
  var busy = false;
  window.__asImgDlCurrent = function () {
    var list = targets();
    if (list.length === 0) return 'none';
    if (busy) return 'busy';
    var best = 0, bestArea = -1, H = window.innerHeight, W = window.innerWidth;
    list.forEach(function (t, i) {
      var r = t.el.getBoundingClientRect();
      var vh = Math.min(r.bottom, H) - Math.max(r.top, 0);
      var vw = Math.min(r.right, W) - Math.max(r.left, 0);
      var area = (vh > 0 && vw > 0) ? vh * vw : 0;
      if (area > bestArea) { bestArea = area; best = i; }
    });
    busy = true;
    saveTarget(list[best], best + 1).catch(function () {}).then(function () { busy = false; });
    return 'saved';
  };
})();
"""

        // یوزرایجنت یه مرورگر معمولی دسکتاپ — بعضی سرورها (مثل رادیوهای اینترنتی) درخواست‌های
        // UA پیش‌فرض وب‌ویو اندروید رو رد می‌کنن یا کانکشن رو ری‌ست می‌کنن؛ با این UA اون مشکل حل می‌شه.
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // پکیج‌نیم اپ‌های فروشگاه — برای اینکه لینک مستقیم به خودشون باز بشه، نه مرورگر داخلی
        private const val PKG_GOOGLE_PLAY = "com.android.vending"
        private const val PKG_CAFE_BAZAAR = "com.farsitel.bazaar"
        private const val PKG_MYKET = "ir.mservices.market"
    }

    private lateinit var binding: ActivityWebviewBinding
    private var currentUrl: String = ""
    private var didAutoRetry: Boolean = false
    private var imageDownloadEnabled: Boolean = false

    // انتخاب فایل برای صفحه‌های HTML داخل وب‌ویو (مثلاً «بازیابی پشتیبان» در وقت سالن)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // اسمی که صفحه‌ی HTML برای فایل بعدیِ دانلود پیشنهاد می‌دهد (مثلاً vaght-salon-backup-1405-08-05.json)
    private var pendingDownloadName: String? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                val single = data.data
                when {
                    clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    single != null -> arrayOf(single)
                    else -> null
                }
            } else {
                null
            }
            // اگر کاربر انصراف داد باید null بدهیم، وگرنه دفعه‌ی بعد انتخاب فایل کار نمی‌کند
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        imageDownloadEnabled = intent.getBooleanExtra(EXTRA_ALLOW_IMAGE_DOWNLOAD, false)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.txtToolbarTitle.text = title

        // لینک‌های پلی‌استور / کافه‌بازار / مایکت اصلاً وارد وب‌ویو نمی‌شن؛
        // مستقیم با اپ خودشون باز می‌شن و این صفحه بسته می‌شه
        if (currentUrl.isNotBlank() && tryOpenInStoreApp(currentUrl)) {
            finish()
            return
        }

        if (imageDownloadEnabled) {
            // فلش دانلود بالای صفحه: عکسی که الان تو صفحه دیده می‌شه رو دانلود می‌کنه (یکی‌یکی)
            binding.btnDownload.visibility = View.VISIBLE
            binding.btnDownload.setOnClickListener {
                if (!DownloadHelper.ensureStoragePermission(this)) return@setOnClickListener
                binding.webView.evaluateJavascript("(window.__asImgDlCurrent ? window.__asImgDlCurrent() : 'none')") { r ->
                    val msg = when (r?.trim('"')) {
                        "saved" -> "در حال ذخیره‌ی عکس…"
                        "busy" -> "عکس قبلی هنوز در حال ذخیره است"
                        else -> "هنوز عکسی برای دانلود آماده نیست"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val allowDownload = intent.getBooleanExtra(EXTRA_ALLOW_DOWNLOAD, false)
        if (allowDownload && !imageDownloadEnabled) {
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
        val allowExternalBrowser = intent.getBooleanExtra(EXTRA_ALLOW_EXTERNAL_BROWSER, true)
        binding.btnOpenBrowser.visibility = if (allowExternalBrowser) View.VISIBLE else View.GONE
        binding.btnOpenBrowser.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
            } catch (e: Exception) {
                // ignore
            }
        }
        binding.btnRetry.setOnClickListener { reload() }

        val desktopMode = intent.getBooleanExtra(EXTRA_DESKTOP_MODE, true)
        setupWebView(desktopMode)
        setupDownloadHandling(title)
        if (currentUrl.isNotBlank()) {
            loadSmart(currentUrl)
        }
    }

    /**
     * اگه لینک مال گوگل‌پلی، کافه‌بازار یا مایکت باشه، مستقیم با همون اپ باز می‌کنه
     * (یا اگه اپ نصب نبود، با مرورگر پیش‌فرض گوشی — نه وب‌ویوی داخلی).
     * خروجی true یعنی «این لینک این‌جا هندل شد، لازم نیست تو وب‌ویو لود بشه».
     */
    private fun tryOpenInStoreApp(url: String): Boolean {
        val storePackage = storePackageFor(url) ?: return false

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(storePackage)
            })
            return true
        } catch (e: Exception) {
            // اپ فروشگاه مربوطه نصب نیست -> با مرورگر پیش‌فرض گوشی باز کن
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun storePackageFor(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("play.google.com") || lower.startsWith("market://") -> PKG_GOOGLE_PLAY
            lower.contains("cafebazaar.ir") || lower.startsWith("bazaar://") -> PKG_CAFE_BAZAAR
            lower.contains("myket.ir") || lower.startsWith("myket://") -> PKG_MYKET
            else -> null
        }
    }

    private fun setupDownloadHandling(pageTitle: String) {
        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onBlobReady(base64DataUri: String, suggestedName: String) {
                runOnUiThread { saveGeneratedFile(base64DataUri, nameFor(suggestedName, pageTitle)) }
            }

            @JavascriptInterface
            fun setNextName(name: String) {
                pendingDownloadName = name
            }

            // اشتراک‌گذاری فایل ساخته‌شده توسط صفحه (مثلاً عکس نتیجه) از طریق پنجره‌ی اشتراک‌گذاری اندروید.
            // وب‌ویو از Web Share API پشتیبانی نمی‌کند، پس صفحه باید همین تابع را صدا بزند.
            @JavascriptInterface
            fun shareFile(dataUri: String, fileName: String) {
                runOnUiThread { shareDataUri(dataUri, fileName) }
            }

            @JavascriptInterface
            fun shareText(text: String) {
                runOnUiThread { shareTextNow(text) }
            }

            @JavascriptInterface
            fun requestPrint() {
                runOnUiThread { printCurrentPage(pageTitle) }
            }
        }, "AndroidDownloader")

        binding.webView.setDownloadListener { url, _, _, mimetype, _ ->
            when {
                url.startsWith("data:") -> {
                    saveGeneratedFile(url, nameFor("", pageTitle))
                }
                url.startsWith("blob:") -> {
                    val js = """
                        (function() {
                            fetch('$url').then(r => r.blob()).then(blob => {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    AndroidDownloader.onBlobReady(reader.result, '');
                                };
                                reader.readAsDataURL(blob);
                            });
                        })();
                    """.trimIndent()
                    binding.webView.evaluateJavascript(js, null)
                }
                else -> {
                    if (DownloadHelper.ensureStoragePermission(this)) {
                        DownloadHelper.downloadUrl(this, url, pageTitle)
                    }
                }
            }
        }
    }

    private fun shareTextNow(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
        } catch (e: Exception) {
            Toast.makeText(this, "اشتراک‌گذاری انجام نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDataUri(dataUri: String, fileName: String) {
        lifecycleScope.launch {
            try {
                val shared = withContext(Dispatchers.IO) {
                    val comma = dataUri.indexOf(',')
                    require(dataUri.startsWith("data:") && comma > 0)
                    val header = dataUri.substring(5, comma)
                    val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
                    val bytes = if (header.contains("base64")) {
                        Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
                    } else {
                        Uri.decode(dataUri.substring(comma + 1)).toByteArray(Charsets.UTF_8)
                    }
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    val safeName = fileName.ifBlank { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(dir, safeName)
                    file.writeBytes(bytes)
                    Pair(FileProvider.getUriForFile(this@WebViewActivity, "$packageName.fileprovider", file), mime)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = shared.second
                    putExtra(Intent.EXTRA_STREAM, shared.first)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
            } catch (e: Exception) {
                Toast.makeText(this@WebViewActivity, "اشتراک‌گذاری انجام نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** اسم فایل: اول اسم صریح، بعد اسمی که صفحه پیشنهاد داده، در آخر عنوان صفحه. */
    private fun nameFor(suggested: String, pageTitle: String): String {
        val pending = pendingDownloadName
        pendingDownloadName = null
        return suggested.ifBlank { pending?.takeIf { it.isNotBlank() } ?: pageTitle }
    }

    private fun saveGeneratedFile(dataUri: String, suggestedName: String) {
        if (!DownloadHelper.ensureStoragePermission(this)) return
        val success = DownloadHelper.saveDataUri(this, dataUri, suggestedName)
        val message = if (success) getString(R.string.download_saved) else getString(R.string.error_loading)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** دیالوگ چاپ اندروید را برای صفحه‌ی فعلی باز می‌کند؛ کاربر از همان‌جا «Save as PDF» را انتخاب می‌کند. */
    private fun printCurrentPage(jobName: String) {
        try {
            val printManager = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val adapter = binding.webView.createPrintDocumentAdapter(jobName.ifBlank { "document" })
            printManager.print(
                jobName.ifBlank { "document" },
                adapter,
                android.print.PrintAttributes.Builder().build()
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun setupWebView(desktopMode: Boolean) {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // فقط وقتی آیتم صراحتاً خواسته دسکتاپ باشه UA رو عوض می‌کنیم؛
        // وگرنه UA پیش‌فرض وب‌ویو (موبایل) دست‌نخورده می‌مونه
        if (desktopMode) {
            settings.userAgentString = DESKTOP_USER_AGENT
        }
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false

                // لینک پلی‌استور/بازار/مایکت وسط مرورگر داخلی هم که باشه، بازم با اپ خودش باز می‌شه
                if (storePackageFor(url) != null) {
                    tryOpenInStoreApp(url)
                    return true
                }

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // آدرس‌های مخصوص اپ‌ها (مثل et:// برای ایتا، tg:// برای تلگرام و...) را
                // به خود سیستم اندروید می‌سپاریم تا اپ مربوطه را باز کند — دقیقاً مثل مرورگر واقعی
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    Toast.makeText(this@WebViewActivity, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
                    true
                }
            }

            // اگر پروکسی HTTP یوزر/پسورد داشته باشد، وب‌ویو از اینجا آن را می‌خواهد
            override fun onReceivedHttpAuthRequest(
                view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
            ) {
                val p = ProxyManager.active
                if (handler != null && p != null && p.type == "http" && !p.user.isNullOrBlank() &&
                    host.equals(p.host, ignoreCase = true)
                ) {
                    handler.proceed(p.user, p.pass ?: "")
                    return
                }
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showError(false)
                view?.evaluateJavascript(SHARE_POLYFILL_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                didAutoRetry = false
                // WebView به window.print() هیچ واکنشی نشون نمی‌ده؛ آن را به دیالوگ چاپ اندروید وصل می‌کنیم
                view?.evaluateJavascript(
                    "window.print = function() { AndroidDownloader.requestPrint(); };", null
                )
                view?.evaluateJavascript(SHARE_POLYFILL_JS, null)
                if (imageDownloadEnabled) view?.evaluateJavascript(IMAGE_DOWNLOAD_JS, null)
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

            // بدون این تابع، هر <input type="file"> داخل وب‌ویو بی‌صدا کار نمی‌کند
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                // اگر درخواست قبلی هنوز باز مانده، آن را ببند
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // */* عمداً: بعضی گوشی‌ها فایل .json را application/octet-stream می‌شناسند و فیلتر نوع، آن را پنهان می‌کند
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(pickIntent, "انتخاب فایل"))
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    filePathCallback?.onReceiveValue(null)
                    Toast.makeText(this@WebViewActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
                    false
                }
            }

            // پنجره‌های alert / confirm / prompt صفحه‌ها: بدون عنوان و بدون نمایش آدرس سایت
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (result == null) return false
                if (isFinishing) { result.cancel(); return true }
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (result == null) return false
                if (isFinishing) { result.cancel(); return true }
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                if (result == null) return false
                if (isFinishing) { result.cancel(); return true }
                val input = android.widget.EditText(this@WebViewActivity).apply {
                    setText(defaultValue ?: "")
                    setSingleLine(true)
                }
                AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
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

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
