package com.kafinet.asannet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * لیست پروکسی‌ها: از جدول «proxies» در Supabase (که از پنل مدیریت پر می‌شود) خوانده و در گوشی ذخیره می‌شود،
 * تا اگر بعداً سوپابیس هم فیلتر بود، کاربر باز هم لیست قبلی را ببیند.
 * پروکسی‌هایی که خود کاربر دستی اضافه کرده هم جدا نگه داشته می‌شود.
 */
object ProxyRepository {

    private const val PREFS = "proxy_lists"
    private const val KEY_CACHE = "cached"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_LAST_REPORT = "last_report"
    private const val REPORT_GAP_MS = 60 * 1000L   // فقط برای جلوگیری از ارسال پشت‌سرهم (اسپم)

    fun cached(context: Context): List<ProxyEntry> = readList(context, KEY_CACHE)

    fun custom(context: Context): List<ProxyEntry> = readList(context, KEY_CUSTOM)

    /** لیست کامل (سرور + دستی) بدون تکرار. */
    fun all(context: Context): List<ProxyEntry> =
        (cached(context) + custom(context)).distinctBy { it.id }

    fun addCustom(context: Context, entries: List<ProxyEntry>) {
        val merged = (custom(context) + entries).distinctBy { it.id }
        writeList(context, KEY_CUSTOM, merged)
        ProxyManager.refreshKnown(context)
    }

    fun removeCustom(context: Context, entry: ProxyEntry) {
        writeList(context, KEY_CUSTOM, custom(context).filterNot { it.id == entry.id })
        ProxyManager.refreshKnown(context)
    }

    /** همه‌ی پروکسی‌هایی که خود کاربر دستی اضافه کرده را پاک می‌کند (لیست سرور دست نمی‌خورد). */
    fun clearCustom(context: Context) {
        writeList(context, KEY_CUSTOM, emptyList())
        ProxyManager.refreshKnown(context)
    }

    fun isCustom(context: Context, entry: ProxyEntry): Boolean =
        custom(context).any { it.id == entry.id } && cached(context).none { it.id == entry.id }

    /**
     * لیست تازه را از سوپابیس می‌گیرد. اول مستقیم، و اگر نشد از طریق پروکسی‌ای که الان وصل است.
     * اگر هر دو شکست خورد null برمی‌گرداند (و لیست قدیمیِ ذخیره‌شده سر جایش می‌ماند).
     */
    suspend fun fetchRemote(context: Context): List<ProxyEntry>? = withContext(Dispatchers.IO) {
        val base = context.getString(R.string.supabase_url).trimEnd('/')
        val key = context.getString(R.string.supabase_anon_key)
        if (base.isBlank() || key.isBlank()) return@withContext null

        val routes: List<Proxy> = listOfNotNull(Proxy.NO_PROXY, ProxyManager.active?.toJavaProxy()).distinct()
        for (route in routes) {
            try {
                val url = URL("$base/rest/v1/proxies?select=*&enabled=eq.true&order=sort_order.asc,created_at.asc&limit=1000")
                val conn = url.openConnection(route) as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("apikey", key)
                conn.setRequestProperty("Authorization", "Bearer $key")
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()

                val arr = JSONArray(body)
                val list = ArrayList<ProxyEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    // نام ستون‌های جدول: username / password  →  user / pass
                    val mapped = JSONObject().apply {
                        put("name", o.optString("name", ""))
                        put("type", o.optString("type", "socks5"))
                        put("host", o.optString("host", ""))
                        put("port", o.optInt("port", 0))
                        put("user", if (o.isNull("username")) "" else o.optString("username", ""))
                        put("pass", if (o.isNull("password")) "" else o.optString("password", ""))
                    }
                    ProxyEntry.fromJson(mapped)?.let { list.add(it) }
                }
                val result = list.distinctBy { it.id }
                writeList(context, KEY_CACHE, result)
                ProxyManager.refreshKnown(context)
                return@withContext result
            } catch (e: Exception) {
                // مسیر بعدی را امتحان کن
            }
        }
        null
    }

    /**
     * نتیجه‌ی تست پروکسی‌های لیست سرور را (فقط «این پروکسی کار کرد یا نه»، بدون هیچ اطلاعات شخصی) برای مدیر می‌فرستد
     * تا از پنل بتواند پروکسی‌هایی را که همه جا خراب‌اند پاک کند.
     * هر گوشی حداکثر هر ۳۰ دقیقه یک‌بار گزارش می‌دهد. results: شناسه‌ی پروکسی و پینگ (‎-1 یعنی خراب)
     */
    suspend fun reportResults(context: Context, results: List<Pair<String, Long>>) = withContext(Dispatchers.IO) {
        if (results.isEmpty()) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_REPORT, 0L) < REPORT_GAP_MS) return@withContext
        val base = context.getString(R.string.supabase_url).trimEnd('/')
        val key = context.getString(R.string.supabase_anon_key)
        if (base.isBlank() || key.isBlank()) return@withContext

        val arr = JSONArray()
        results.take(1000).forEach { (id, ms) ->
            arr.put(JSONObject().apply {
                put("proxy_key", id)
                put("ok", ms > 0)
                put("ms", if (ms > 0) ms else JSONObject.NULL)
            })
        }
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        val routes: List<Proxy> = listOfNotNull(Proxy.NO_PROXY, ProxyManager.active?.toJavaProxy()).distinct()
        for (route in routes) {
            try {
                val conn = URL("$base/rest/v1/proxy_reports").openConnection(route) as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("apikey", key)
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    prefs.edit().putLong(KEY_LAST_REPORT, now).apply()
                    return@withContext
                }
            } catch (e: Exception) {
                // مسیر بعدی
            }
        }
    }

    private fun readList(context: Context, key: String): List<ProxyEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { ProxyEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(context: Context, key: String, list: List<ProxyEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }
}
