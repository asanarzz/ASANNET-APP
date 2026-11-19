package com.kafinet.asannet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** میانگین امتیاز و تعداد رأی‌های یک فیلم/سریال. */
data class RatingStat(val average: Double, val votes: Int) {
    /** مثلاً 7.8 یا 8 (اگه اعشار صفر باشه نشون داده نمی‌شه). */
    fun averageText(): String {
        val rounded = Math.round(average * 10) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else String.format(Locale.US, "%.1f", rounded)
    }
}

/**
 * امتیازدهی ۱ تا ۱۰ به فیلم و سریال (شبیه IMDb) — داده‌ها تو Supabase (جدول ratings و ویوی
 * rating_stats) نگهداری می‌شن. اگه جدول هنوز ساخته نشده باشه یا اینترنت نباشه، همه‌چیز بی‌صدا
 * خالی می‌مونه و هیچ خطایی به کاربر نشون داده نمی‌شه.
 */
object RatingStore {

    @Volatile
    private var stats: Map<String, RatingStat> = emptyMap()

    fun get(itemId: String): RatingStat? = stats[itemId]

    /** شناسه‌ی رأی‌دهنده: هش کد ملی کاربر (نه خود کد ملی)، یا یه شناسه‌ی تصادفی رو همین گوشی. */
    private fun voterId(context: Context): String {
        val national = SessionManager.getNationalCode(context)?.takeIf { it.isNotBlank() }
        val raw = national ?: deviceId(context)
        val digest = MessageDigest.getInstance("SHA-256").digest("kafinet-rating:$raw".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("kafinet_rating", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    private fun open(context: Context, path: String, method: String): HttpURLConnection? {
        val baseUrl = context.getString(R.string.supabase_url).trimEnd('/')
        val anonKey = context.getString(R.string.supabase_anon_key)
        if (baseUrl.isBlank() || anonKey.isBlank()) return null
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("apikey", anonKey)
        connection.setRequestProperty("Authorization", "Bearer $anonKey")
        return connection
    }

    /** آخرین میانگین‌ها رو از سرور می‌گیره. true یعنی چیزی نسبت به قبل عوض شده. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = open(context, "/rest/v1/rating_stats?select=item_id,avg_score,votes", "GET")
                ?: return@withContext false
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) return@withContext false

            val array = JSONArray(text)
            val map = HashMap<String, RatingStat>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("item_id", "")
                val votes = obj.optInt("votes", 0)
                if (id.isBlank() || votes <= 0) continue
                map[id] = RatingStat(obj.optDouble("avg_score", 0.0), votes)
            }
            val changed = map != stats
            stats = map
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** امتیازی که خود این کاربر قبلاً به این مورد داده (یا null). */
    suspend fun myVote(context: Context, itemId: String): Int? = withContext(Dispatchers.IO) {
        try {
            val id = URLEncoder.encode(itemId, "UTF-8")
            val voter = URLEncoder.encode(voterId(context), "UTF-8")
            val connection = open(context, "/rest/v1/ratings?select=score&item_id=eq.$id&voter_id=eq.$voter", "GET")
                ?: return@withContext null
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) return@withContext null
            val array = JSONArray(text)
            if (array.length() == 0) null else array.getJSONObject(0).optInt("score", 0).takeIf { it in 1..10 }
        } catch (e: Exception) {
            null
        }
    }

    /** امتیاز ۱ تا ۱۰ رو ثبت می‌کنه (اگه قبلاً داده بود، عوض می‌شه). true یعنی موفق. */
    suspend fun submit(context: Context, itemId: String, score: Int): Boolean = withContext(Dispatchers.IO) {
        if (score !in 1..10 || itemId.isBlank()) return@withContext false
        try {
            val connection = open(context, "/rest/v1/ratings?on_conflict=item_id,voter_id", "POST")
                ?: return@withContext false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            val body = JSONObject().apply {
                put("item_id", itemId)
                put("voter_id", voterId(context))
                put("score", score)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
