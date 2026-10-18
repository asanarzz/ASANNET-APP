package com.kafinet.asannet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemAdBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** یک آگهی. status فقط برای «آگهی‌های من» معنی دارد (pending / approved / rejected). */
data class Ad(
    val id: String,
    val createdAt: String,
    val title: String,
    val description: String,
    val price: Long?,
    val category: String,
    val city: String,
    val phone: String,
    val sellerName: String,
    val images: ArrayList<String>,
    val status: String = "approved",
    val rejectReason: String? = null,
    val allowCall: Boolean = true,
    val allowSms: Boolean = true,
    val allowChat: Boolean = true,
    val eitaaId: String = ""
) : Serializable

class AdsResult(val ads: List<Ad>, val error: Boolean)

object AdCategories {
    val all: List<String> = listOf(
        "وسایل نقلیه",
        "املاک",
        "موبایل و تبلت",
        "کالای دیجیتال",
        "لوازم خانگی",
        "خانه و آشپزخانه",
        "مبلمان و دکوراسیون",
        "پوشاک و اکسسوری",
        "ورزش و سرگرمی",
        "لوازم کودک",
        "کتاب و لوازم تحریر",
        "خدمات",
        "استخدام و کار",
        "سایر"
    )
}

object AdsFormat {

    /** ارقام فارسی و عربی را به انگلیسی تبدیل می‌کند. */
    fun toEnglishDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun price(p: Long?): String {
        if (p == null || p <= 0L) return "توافقی"
        return String.format(Locale.US, "%,d", p) + " تومان"
    }

    fun timeAgo(iso: String): String {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val date = fmt.parse(iso.take(19))
            if (date == null) {
                ""
            } else {
                val diff = (System.currentTimeMillis() - date.time) / 1000
                when {
                    diff < 60 -> "لحظاتی پیش"
                    diff < 3600 -> "${diff / 60} دقیقه پیش"
                    diff < 86400 -> "${diff / 3600} ساعت پیش"
                    diff < 86400 * 30 -> "${diff / 86400} روز پیش"
                    else -> "${diff / (86400 * 30)} ماه پیش"
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}

object AdsClient {

    internal fun base(context: Context): String = context.getString(R.string.supabase_url).trimEnd('/')
    internal fun key(context: Context): String = context.getString(R.string.supabase_anon_key)

    /**
     * یک کد تصادفی که فقط روی همین گوشی (برای همین کاربر) ذخیره می‌شه و مالکیت آگهی‌ها
     * با اون مشخص می‌شه. کد ملی هیچ‌وقت توی لیست عمومی آگهی‌ها نمایش داده نمی‌شه.
     */
    fun ownerToken(context: Context): String {
        val code = SessionManager.getNationalCode(context).orEmpty()
        val prefs = context.getSharedPreferences("kafinet_ads", Context.MODE_PRIVATE)
        val prefKey = "owner_token_$code"
        val existing = prefs.getString(prefKey, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(prefKey, created).apply()
        return created
    }

    internal fun openConn(context: Context, path: String, method: String, timeoutMs: Int = 10000): HttpURLConnection {
        val conn = URL(base(context) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("apikey", key(context))
        conn.setRequestProperty("Authorization", "Bearer " + key(context))
        return conn
    }

    internal fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    internal fun writeJson(conn: HttpURLConnection, body: JSONObject) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun parseAds(text: String): List<Ad> {
        val arr = JSONArray(text)
        val out = ArrayList<Ad>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val imgs = ArrayList<String>()
            val ia = o.optJSONArray("images")
            if (ia != null) {
                for (j in 0 until ia.length()) {
                    val u = ia.optString(j, "")
                    if (u.isNotBlank()) imgs.add(u)
                }
            }
            out.add(
                Ad(
                    id = o.optString("id"),
                    createdAt = o.optString("created_at"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    price = if (o.isNull("price")) null else o.optLong("price"),
                    category = o.optString("category"),
                    city = o.optString("city"),
                    phone = o.optString("phone"),
                    sellerName = o.optString("seller_name"),
                    images = imgs,
                    status = o.optString("status", "approved"),
                    rejectReason = if (o.isNull("reject_reason")) null else o.optString("reject_reason"),
                    allowCall = o.optBoolean("allow_call", true),
                    allowSms = o.optBoolean("allow_sms", true),
                    allowChat = o.optBoolean("allow_chat", true),
                    eitaaId = o.optString("eitaa_id", "")
                )
            )
        }
        return out
    }

    /** آگهی‌های تأییدشده (از نمای عمومی ads_public). */
    suspend fun fetchApproved(context: Context): AdsResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(context, "/rest/v1/ads_public?select=*&order=created_at.desc&limit=300", "GET")
            val code = conn.responseCode
            val text = readBody(conn)
            conn.disconnect()
            if (code !in 200..299) return@withContext AdsResult(emptyList(), true)
            AdsResult(parseAds(text), false)
        } catch (e: Exception) {
            AdsResult(emptyList(), true)
        }
    }

    /** آگهی‌های خودِ کاربر (با هر وضعیتی). */
    suspend fun fetchMine(context: Context): AdsResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(context, "/rest/v1/rpc/my_ads", "POST")
            writeJson(conn, JSONObject().put("p_token", ownerToken(context)))
            val code = conn.responseCode
            val text = readBody(conn)
            conn.disconnect()
            if (code !in 200..299) return@withContext AdsResult(emptyList(), true)
            AdsResult(parseAds(text), false)
        } catch (e: Exception) {
            AdsResult(emptyList(), true)
        }
    }

    /** عکس‌ها را آپلود می‌کنه و آگهی رو با وضعیت «در انتظار تأیید» ثبت می‌کنه. */
    suspend fun createAd(
        context: Context,
        title: String,
        description: String,
        price: Long?,
        category: String,
        city: String,
        phone: String,
        sellerName: String,
        images: List<ByteArray>,
        allowCall: Boolean,
        allowSms: Boolean,
        allowChat: Boolean,
        eitaaId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val urls = JSONArray()
            for ((i, bytes) in images.withIndex()) {
                val path = "ads/" + System.currentTimeMillis() + "-" + i + "-" +
                    UUID.randomUUID().toString().take(8) + ".jpg"
                val up = openConn(context, "/storage/v1/object/ads-images/$path", "POST", 30000)
                up.doOutput = true
                up.setRequestProperty("Content-Type", "image/jpeg")
                up.setFixedLengthStreamingMode(bytes.size)
                up.outputStream.use { it.write(bytes) }
                val upCode = up.responseCode
                up.disconnect()
                if (upCode !in 200..299) return@withContext false
                urls.put(base(context) + "/storage/v1/object/public/ads-images/" + path)
            }

            val conn = openConn(context, "/rest/v1/ads", "POST")
            conn.setRequestProperty("Prefer", "return=minimal")
            val body = JSONObject()
            body.put("title", title)
            body.put("description", description)
            body.put("price", price ?: JSONObject.NULL)
            body.put("category", category)
            body.put("city", city)
            body.put("phone", phone)
            body.put("seller_name", sellerName)
            body.put("images", urls)
            body.put("allow_call", allowCall)
            body.put("allow_sms", allowSms)
            body.put("allow_chat", allowChat)
            body.put("eitaa_id", eitaaId)
            body.put("owner_code", SessionManager.getNationalCode(context).orEmpty())
            body.put("owner_token", ownerToken(context))
            writeJson(conn, body)
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteMine(context: Context, adId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(context, "/rest/v1/rpc/delete_my_ad", "POST")
            val body = JSONObject()
            body.put("p_id", adId)
            body.put("p_token", ownerToken(context))
            writeJson(conn, body)
            val code = conn.responseCode
            val text = readBody(conn)
            conn.disconnect()
            code in 200..299 && text.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    /** عکس انتخاب‌شده را کوچک می‌کنه (حداکثر ۱۲۸۰ پیکسل)، چرخش EXIF رو درست می‌کنه و JPEG برمی‌گردونه. */
    fun compressImage(context: Context, uri: Uri): ByteArray? {
        return try {
            val maxSide = 1280
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            var degrees = 0
            try {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val exif = android.media.ExifInterface(s)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    degrees = when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
                degrees = 0
            }

            val w = decoded.width
            val h = decoded.height
            val scale = maxSide.toFloat() / maxOf(w, h).toFloat()
            var bmp: Bitmap = decoded
            if (scale < 1f || degrees != 0) {
                val m = Matrix()
                if (scale < 1f) m.postScale(scale, scale)
                if (degrees != 0) m.postRotate(degrees.toFloat())
                val scaled = Bitmap.createBitmap(decoded, 0, 0, w, h, m, true)
                if (scaled !== decoded) decoded.recycle()
                bmp = scaled
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            null
        }
    }
}

/** کارت‌های دوستونه‌ی لیست آگهی‌ها (مثل دیوار). */
class AdsAdapter(
    private var items: List<Ad>,
    private val onClick: (Ad) -> Unit
) : RecyclerView.Adapter<AdsAdapter.VH>() {

    class VH(val b: ItemAdBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemAdBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ad = items[position]
        holder.b.txtTitle.text = ad.title
        holder.b.txtPrice.text = AdsFormat.price(ad.price)
        holder.b.txtMeta.text = listOf(ad.city, AdsFormat.timeAgo(ad.createdAt))
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        val first = ad.images.firstOrNull()
        if (first != null) {
            Glide.with(holder.b.imgAd.context).load(first).centerCrop().into(holder.b.imgAd)
        } else {
            Glide.with(holder.b.imgAd.context).clear(holder.b.imgAd)
            holder.b.imgAd.setImageDrawable(null)
        }
        holder.b.root.setOnClickListener { onClick(ad) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<Ad>) {
        items = newItems
        notifyDataSetChanged()
    }
}
