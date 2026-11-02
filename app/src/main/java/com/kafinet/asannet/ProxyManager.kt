package com.kafinet.asannet

import android.content.Context
import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor

/** یک پروکسی (SOCKS5 یا HTTP). */
data class ProxyEntry(
    val name: String,
    val type: String,          // "socks5" یا "http"
    val host: String,
    val port: Int,
    val user: String? = null,
    val pass: String? = null
) {
    /** شناسه‌ی یکتا برای هر پروکسی */
    val id: String get() = "$type://$host:$port"

    val typeLabel: String get() = if (type == "http") "HTTP" else "SOCKS5"

    fun toJavaProxy(): Proxy = Proxy(
        if (type == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS,
        // createUnresolved: اسم سایت (مثلاً instagram.com) به خودِ پروکسی داده می‌شود و DNS هم
        // آنجا حل می‌شود، پس فیلترینگ DNS هم دور زده می‌شود.
        InetSocketAddress.createUnresolved(host, port)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("type", type); put("host", host); put("port", port)
        put("user", user ?: ""); put("pass", pass ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): ProxyEntry? {
            val host = o.optString("host", "").trim()
            val port = o.optInt("port", 0)
            if (host.isEmpty() || port !in 1..65535) return null
            val type = if (o.optString("type", "socks5").equals("http", true)) "http" else "socks5"
            return ProxyEntry(
                name = o.optString("name", "").ifBlank { "$host:$port" },
                type = type,
                host = host,
                port = port,
                user = o.optString("user", "").ifBlank { null },
                pass = o.optString("pass", "").ifBlank { null }
            )
        }
    }
}

/**
 * مدیر پروکسی داخل خود اپ (بدون VPN و بدون دسترسی ویژه‌ی اندروید) — دقیقاً مثل تلگرام:
 * وقتی کاربر یک پروکسی را وصل می‌کند، همه‌ی ترافیک «خودِ اپ» از آن رد می‌شود:
 *  - اتصال‌های HttpURLConnection (سوپابیس، عکس‌ها با Glide، پخش ویدیو/رادیو با ExoPlayer، بررسی آپدیت و...)
 *  - تمام صفحه‌های وب داخل اپ (WebView)، مثلاً لینک اینستاگرام که در بخش «لینک» می‌گذاری
 * آدرس‌های شبکه‌ی محلی (مثل DLNA و Cast) عمداً مستقیم می‌مانند.
 */
object ProxyManager {

    private const val PREFS = "proxy_prefs"
    private const val KEY_ACTIVE = "active"

    @Volatile
    var active: ProxyEntry? = null
        private set

    /** true یعنی همین الان داریم یک پروکسی را تست می‌کنیم (آیکون زرد می‌شود). */
    @Volatile
    var connecting: Boolean = false

    @Volatile
    private var known: Map<String, ProxyEntry> = emptyMap()   // "host:port" -> پروکسی (برای رمز عبور)

    private var hooksInstalled = false

    /**
     * سایت‌هایی که هیچ‌وقت از پروکسی رد نمی‌شوند، چون یا خودِ سرویس‌های برنامه‌اند (سوپابیس، گیت‌هاب) یا
     * سایت ایرانی‌اند (دامنه‌ی ‎.ir‎) و از پروکسی خارجی کندتر باز می‌شوند یا حتی IP خارجی را قبول نمی‌کنند.
     * پروکسی فقط برای بقیه‌ی سایت‌ها (مثل اینستاگرام) استفاده می‌شود.
     */
    @Volatile
    private var directSuffixes: List<String> = listOf(
        ".ir", ".supabase.co", ".githubusercontent.com", ".github.io", ".github.com"
    )

    private fun isDirectHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val h = host.lowercase()
        return directSuffixes.any { suf -> h == suf.removePrefix(".") || h.endsWith(suf) }
    }

    /** باید در Application.onCreate و قبل از هر درخواست شبکه صدا زده شود. */
    fun init(context: Context) {
        val app = context.applicationContext
        try {
            val sbHost = URI(app.getString(R.string.supabase_url)).host
            if (!sbHost.isNullOrBlank()) directSuffixes = directSuffixes + sbHost.lowercase()
        } catch (e: Exception) {
            // مهم نیست؛ پسوند .supabase.co از قبل در لیست هست
        }
        installHooks()
        refreshKnown(app)
        val saved = loadActive(app)
        active = saved
        if (saved != null) applyToWebView(saved)
    }

    /** پروکسی فعال را عوض می‌کند (null یعنی قطع اتصال) و ذخیره می‌کند تا دفعه‌ی بعد هم وصل بماند. */
    fun setActive(context: Context, entry: ProxyEntry?) {
        val app = context.applicationContext
        if (entry != null) registerKnown(entry)
        active = entry
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (entry == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, entry.toJson().toString())
        }.apply()
        applyToWebView(entry)
    }

    private fun loadActive(context: Context): ProxyEntry? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)
            ?: return null
        return try {
            ProxyEntry.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    /** پروکسی‌های شناخته‌شده را برای پیدا کردن یوزر/پسورد نگه می‌داریم. */
    fun refreshKnown(context: Context) {
        val map = HashMap<String, ProxyEntry>()
        ProxyRepository.all(context).forEach { map["${it.host.lowercase()}:${it.port}"] = it }
        active?.let { map["${it.host.lowercase()}:${it.port}"] = it }
        known = map
    }

    private fun registerKnown(entry: ProxyEntry) {
        known = known + ("${entry.host.lowercase()}:${entry.port}" to entry)
    }

    // ---------------------------------------------------------------------------------------
    // ترافیک عادی اپ (HttpURLConnection / Glide / ExoPlayer ...)
    // ---------------------------------------------------------------------------------------

    private fun installHooks() {
        if (hooksInstalled) return
        hooksInstalled = true

        val original: ProxySelector? = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val p = active
                if (p == null || uri == null) {
                    return original?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return listOf(Proxy.NO_PROXY)
                if (isLocalHost(uri.host) || isDirectHost(uri.host)) return listOf(Proxy.NO_PROXY)
                return listOf(p.toJavaProxy())
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                original?.connectFailed(uri, sa, ioe)
            }
        })

        // یوزر و پسورد پروکسی (اگر داشته باشد) — فقط برای خودِ آدرس پروکسی داده می‌شود
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val host = requestingHost?.lowercase() ?: return null
                val entry = known["$host:$requestingPort"] ?: return null
                val u = entry.user ?: return null
                return PasswordAuthentication(u, (entry.pass ?: "").toCharArray())
            }
        })
    }

    /** آدرس‌های داخل شبکه‌ی خانه/گوشی که نباید از پروکسی رد شوند. */
    private fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || !h.contains('.') && !h.contains(':')) return true
        if (h.startsWith("10.") || h.startsWith("127.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true
        if (h.startsWith("172.")) {
            val second = h.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // صفحه‌های وب داخل اپ (WebView)
    // ---------------------------------------------------------------------------------------

    private fun applyToWebView(entry: ProxyEntry?) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
            val executor = Executor { it.run() }
            val controller = ProxyController.getInstance()
            if (entry == null) {
                controller.clearProxyOverride(executor) { }
                return
            }
            val scheme = if (entry.type == "http") "http" else "socks5"
            val rule = "$scheme://${entry.host}:${entry.port}"
            try {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .addBypassRule("localhost")
                    .addBypassRule("*.local")
                    .addBypassRule("*.ir")
                    .addBypassRule("*.supabase.co")
                    .addBypassRule("*.githubusercontent.com")
                    .addBypassRule("*.github.io")
                    .addBypassRule("github.com")
                    .addBypassRule("127.0.0.0/8")
                    .addBypassRule("10.0.0.0/8")
                    .addBypassRule("172.16.0.0/12")
                    .addBypassRule("192.168.0.0/16")
                    .build()
                controller.setProxyOverride(cfg, executor) { }
            } catch (e: Exception) {
                // اگر قاعده‌های استثنا روی این نسخه‌ی وب‌ویو قبول نشد، بدون آن‌ها اعمال کن
                val cfg = ProxyConfig.Builder().addProxyRule(rule).build()
                controller.setProxyOverride(cfg, executor) { }
            }
        } catch (e: Throwable) {
            android.util.Log.w("ProxyManager", "WebView proxy failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // تست سرعت/سالم بودن پروکسی
    // ---------------------------------------------------------------------------------------

    /**
     * یک درخواست واقعی HTTPS را از داخل همین پروکسی می‌فرستد.
     * اگر جواب بدهد، پروکسی واقعاً کار می‌کند و پینگ (میلی‌ثانیه) برمی‌گردد؛ وگرنه null.
     */
    suspend fun test(entry: ProxyEntry, timeoutMs: Int = 7000, quick: Boolean = false): Long? =
        withContext(Dispatchers.IO) {
            val ms = doTest(entry, timeoutMs, quick)
            pingCache[entry.id] = Pair(ms ?: -1L, System.currentTimeMillis())
            ms
        }

    // نتیجه‌ی تست‌ها ۱۰ دقیقه نگه داشته می‌شود تا هر بار که پنجره باز می‌شود از اول تست نشود
    private val pingCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private const val PING_CACHE_MS = 10 * 60 * 1000L

    /** آخرین نتیجه‌ی تست این پروکسی (اگر کمتر از ۱۰ دقیقه گذشته باشد)؛ ‎-1 یعنی کار نمی‌کرد. */
    fun cachedPing(id: String): Long? {
        val p = pingCache[id] ?: return null
        return if (System.currentTimeMillis() - p.second < PING_CACHE_MS) p.first else null
    }

    private fun doTest(entry: ProxyEntry, timeoutMs: Int, quick: Boolean): Long? {
        registerKnown(entry)
        val start = System.nanoTime()
        // تست سریع: اول فقط ببین پورت پروکسی اصلاً باز است یا نه. پروکسی‌های مرده همین‌جا
        // در کمتر از ۲ ثانیه کنار گذاشته می‌شوند و منتظر تایم‌اوت کامل نمی‌مانیم.
        if (quick) {
            try {
                java.net.Socket().use { s -> s.connect(InetSocketAddress(entry.host, entry.port), 2000) }
            } catch (e: Exception) {
                return null
            }
        }
        return try {
            val conn = URL("https://www.google.com/generate_204").openConnection(entry.toJavaProxy()) as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) (System.nanoTime() - start) / 1_000_000 else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // خواندن پروکسی از متن (لینک تلگرام، socks5://host:port، host:port ...)
    // ---------------------------------------------------------------------------------------

    /** برمی‌گرداند null اگر متن پروکسی قابل‌استفاده نباشد (مثلاً لینک MTProto تلگرام). */
    fun parse(raw: String): ProxyEntry? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            if (Regex("^(tg://|https?://t\\.me/)proxy", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                null // پروکسی MTProto مخصوص خود تلگرام است و برای این اپ کار نمی‌کند
            } else if (Regex("^(tg://|https?://t\\.me/)socks", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                val u = Uri.parse(s)
                val host = u.getQueryParameter("server") ?: return null
                val port = u.getQueryParameter("port")?.toIntOrNull() ?: return null
                build("socks5", host, port, u.getQueryParameter("user"), u.getQueryParameter("pass"))
            } else {
                val withScheme = if (s.contains("://")) s else "socks5://$s"
                val u = URI(withScheme)
                val type = when (u.scheme?.lowercase()) {
                    "socks5", "socks5h", "socks" -> "socks5"
                    "http", "https" -> "http"
                    else -> return null
                }
                val host = u.host ?: return null
                if (u.port <= 0) return null
                var user: String? = null
                var pass: String? = null
                val ui = u.userInfo
                if (ui != null) {
                    val i = ui.indexOf(':')
                    if (i >= 0) {
                        user = ui.substring(0, i)
                        pass = ui.substring(i + 1)
                    } else {
                        user = ui
                    }
                }
                build(type, host, u.port, user, pass)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun build(type: String, host: String, port: Int, user: String?, pass: String?): ProxyEntry? {
        if (host.isBlank() || port !in 1..65535) return null
        return ProxyEntry(
            name = "$host:$port",
            type = type,
            host = host.trim(),
            port = port,
            user = user?.ifBlank { null },
            pass = pass?.ifBlank { null }
        )
    }
}
