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
                val url = URL("$base/rest/v1/proxies?select=*&enabled=eq.true&order=sort_order.asc,created_at.asc&limit=300")
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
