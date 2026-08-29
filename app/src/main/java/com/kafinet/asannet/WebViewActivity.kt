package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
        setupDownloadHandling(title)
        if (currentUrl.isNotBlank()) {
            loadSmart(currentUrl)
        }
    }

    private fun setupDownloadHandling(pageTitle: String) {
        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onBlobReady(base64DataUri: String, suggestedName: String) {
                runOnUiThread { saveGeneratedFile(base64DataUri, suggestedName.ifBlank { pageTitle }) }
            }

            @JavascriptInterface
            fun requestPrint() {
                runOnUiThread { printCurrentPage(pageTitle) }
            }
        }, "AndroidDownloader")

        binding.webView.setDownloadListener { url, _, _, mimetype, _ ->
            when {
                url.startsWith("data:") -> {
                    saveGeneratedFile(url, pageTitle)
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

    private fun saveGeneratedFile(dataUri: String, suggestedName: String) {
        if (!DownloadHelper.ensureStoragePermission(this)) return
        val success = DownloadHelper.saveDataUri(this, dataUri, suggestedName)
        val message = if (success) getString(R.string.download_saved) else getString(R.string.error_loading)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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
                if (url == null) return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    Toast.makeText(this@WebViewActivity, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showError(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                didAutoRetry = false
                view?.evaluateJavascript(
                    "window.print = function() { AndroidDownloader.requestPrint(); };", null
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
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