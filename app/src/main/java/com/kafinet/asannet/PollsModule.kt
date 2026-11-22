package com.kafinet.asannet

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------- مدل‌ها

data class PollOption(val id: String, val label: String, val votes: Int?)

data class PollDetail(
    val code: String,
    val title: String,
    val description: String,
    val organizer: String,
    val multiple: Boolean,
    val showResults: String,
    val endsAt: String?,
    val closed: Boolean,
    val isOwner: Boolean,
    val voted: Boolean,
    val canSeeResults: Boolean,
    val totalVoters: Int?,
    val options: List<PollOption>,
    val myVotes: Set<String>
)

data class PollSummary(
    val code: String,
    val title: String,
    val createdAt: String,
    val endsAt: String?,
    val closed: Boolean,
    val hidden: Boolean,
    val totalVoters: Int?
)

/** status: ok / not_found / error */
class PollLoad(val poll: PollDetail?, val status: String, val detail: String = "")

class PollCreateResult(val code: String?, val error: String?)

// ---------------------------------------------------------------- ارتباط با Supabase

object PollsClient {

    private const val PREFS = "kafinet_polls"
    private const val KEY_PENDING = "pending_code"

    /** آخرین خطای ارتباط با سرور (برای نمایش علت وقتی چیزی باز نمی‌شود). */
    @Volatile
    var lastError: String = ""

    fun voterCode(context: Context): String = SessionManager.getNationalCode(context).orEmpty()

    /** همان کد مالکیت که برای آگهی‌ها هم استفاده می‌شود (فقط روی همین گوشی). */
    fun token(context: Context): String = AdsClient.ownerToken(context)

    // کدی که از لینک گرفته شده ولی کاربر هنوز ثبت‌نام/ورود نکرده؛ بعد از ورود باز می‌شود.
    fun setPendingCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, code).apply()
    }

    fun takePendingCode(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_PENDING, null)
        if (code != null) prefs.edit().remove(KEY_PENDING).apply()
        return code
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifBlank { null }
    }

    private suspend fun rpc(context: Context, name: String, body: JSONObject): String? =
        withContext(Dispatchers.IO) {
            try {
                val conn = AdsClient.openConn(context, "/rest/v1/rpc/$name", "POST", 12000)
                AdsClient.writeJson(conn, body)
                val code = conn.responseCode
                val text = AdsClient.readBody(conn)
                conn.disconnect()
                if (code in 200..299) {
                    text.trim()
                } else {
                    lastError = "HTTP " + code + ": " + text.take(200)
                    null
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                null
            }
        }

    private fun unquote(s: String): String = s.trim().removeSurrounding("\"")

    suspend fun createPoll(
        context: Context,
        organizer: String,
        title: String,
        description: String,
        options: List<String>,
        multiple: Boolean,
        showResults: String,
        endsAtIso: String?
    ): PollCreateResult {
        val body = JSONObject()
        body.put("p_owner_code", voterCode(context))
        body.put("p_token", token(context))
        body.put("p_organizer", organizer)
        body.put("p_title", title)
        body.put("p_description", description)
        body.put("p_options", JSONArray(options))
        body.put("p_multiple", multiple)
        body.put("p_show_results", showResults)
        body.put("p_ends_at", endsAtIso ?: JSONObject.NULL)
        val text = rpc(context, "create_poll", body) ?: return PollCreateResult(null, "network")
        val v = unquote(text)
        return if (Regex("^[A-Z0-9]{7}$").matches(v)) PollCreateResult(v, null) else PollCreateResult(null, v)
    }

    suspend fun getPoll(context: Context, code: String): PollLoad {
        val body = JSONObject()
        body.put("p_code", code)
        body.put("p_voter_code", voterCode(context))
        body.put("p_token", token(context))
        val text = rpc(context, "get_poll", body) ?: return PollLoad(null, "error", lastError)
        return try {
            val o = JSONObject(text)
            if (!o.optBoolean("found", false)) return PollLoad(null, "not_found")
            val opts = ArrayList<PollOption>()
            val oa = o.optJSONArray("options") ?: JSONArray()
            for (i in 0 until oa.length()) {
                val x = oa.getJSONObject(i)
                opts.add(
                    PollOption(
                        id = x.optString("id"),
                        label = x.optString("label"),
                        votes = if (x.isNull("votes")) null else x.optInt("votes")
                    )
                )
            }
            val mine = HashSet<String>()
            val ma = o.optJSONArray("my_votes") ?: JSONArray()
            for (i in 0 until ma.length()) mine.add(ma.optString(i))
            PollLoad(
                PollDetail(
                    code = o.optString("code"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    organizer = o.optString("organizer"),
                    multiple = o.optBoolean("multiple", false),
                    showResults = o.optString("show_results", "after_vote"),
                    endsAt = o.str("ends_at"),
                    closed = o.optBoolean("closed", false),
                    isOwner = o.optBoolean("is_owner", false),
                    voted = o.optBoolean("voted", false),
                    canSeeResults = o.optBoolean("can_see_results", false),
                    totalVoters = if (o.isNull("total_voters")) null else o.optInt("total_voters"),
                    options = opts,
                    myVotes = mine
                ),
                "ok"
            )
        } catch (e: Exception) {
            PollLoad(null, "error", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** نتیجه: ok / already / closed / invalid / no_user / not_found / error */
    suspend fun vote(context: Context, code: String, optionIds: List<String>): String {
        val body = JSONObject()
        body.put("p_code", code)
        body.put("p_voter_code", voterCode(context))
        body.put("p_option_ids", JSONArray(optionIds))
        val text = rpc(context, "vote_poll", body) ?: return "error"
        return unquote(text)
    }

    private fun parseSummaries(text: String): List<PollSummary> {
        val arr = JSONArray(text)
        val out = ArrayList<PollSummary>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PollSummary(
                    code = o.optString("code"),
                    title = o.optString("title"),
                    createdAt = o.optString("created_at"),
                    endsAt = o.str("ends_at"),
                    closed = o.optBoolean("closed", false),
                    hidden = o.optBoolean("hidden", false),
                    totalVoters = if (o.isNull("total_voters")) null else o.optInt("total_voters")
                )
            )
        }
        return out
    }

    /** null یعنی خطای اینترنت/سرور. */
    suspend fun myPolls(context: Context): List<PollSummary>? {
        val text = rpc(context, "my_polls", JSONObject().put("p_token", token(context))) ?: return null
        return try { parseSummaries(text) } catch (e: Exception) { null }
    }

    suspend fun participated(context: Context): List<PollSummary>? {
        val text = rpc(context, "participated_polls", JSONObject().put("p_voter_code", voterCode(context))) ?: return null
        return try { parseSummaries(text) } catch (e: Exception) { null }
    }

    suspend fun closePoll(context: Context, code: String): Boolean {
        val body = JSONObject().put("p_code", code).put("p_token", token(context))
        return rpc(context, "close_poll", body)?.let { unquote(it) == "true" } ?: false
    }

    suspend fun deletePoll(context: Context, code: String): Boolean {
        val body = JSONObject().put("p_code", code).put("p_token", token(context))
        return rpc(context, "delete_poll", body)?.let { unquote(it) == "true" } ?: false
    }

    /** ok / already / not_found / no_user / error */
    suspend fun report(context: Context, code: String, reason: String): String {
        val body = JSONObject()
            .put("p_code", code)
            .put("p_reporter", voterCode(context))
            .put("p_reason", reason)
        val text = rpc(context, "report_poll", body) ?: return "error"
        return unquote(text)
    }
}

// ---------------------------------------------------------------- ابزارهای نمایش

object PollFormat {

    fun digits(n: Int): String = PersianDateUtils.toPersianDigits(n.toString())

    fun parseIso(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(iso.take(19))?.time
        } catch (e: Exception) {
            null
        }
    }

    /** زمان پایان را برای ارسال به سرور (UTC) می‌سازد. */
    fun isoFromNow(hours: Int): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(System.currentTimeMillis() + hours * 3600_000L))
    }

    fun timeLeft(endsAt: String?, closed: Boolean): String {
        if (closed) return "پایان‌یافته"
        val end = parseIso(endsAt) ?: return "بدون مهلت"
        val diff = (end - System.currentTimeMillis()) / 1000
        if (diff <= 0) return "پایان‌یافته"
        val text = when {
            diff < 3600 -> "${diff / 60 + 1} دقیقه"
            diff < 86400 -> "${diff / 3600} ساعت"
            else -> "${diff / 86400} روز"
        }
        return PersianDateUtils.toPersianDigits("$text دیگر")
    }

    fun resultsLabel(show: String): String = when (show) {
        "always" -> "نتیجه همیشه نمایش داده می‌شود"
        "after_end" -> "نتیجه بعد از پایان نظرسنجی"
        else -> "نتیجه بعد از رأی دادن"
    }

    /** کد را از هر چیزی که کاربر وارد یا پیست کرده (لینک، «ASN-XXXXXXX» یا خود کد) درمی‌آورد. */
    fun extractCode(raw: String): String? {
        val s = AdsFormat.toEnglishDigits(raw.trim())
        val fromUrl = Regex("(?:[?&]c=|poll/)([A-Za-z0-9]{7})(?![A-Za-z0-9])").find(s)
        if (fromUrl != null) return fromUrl.groupValues[1].uppercase()
        var t = s.filter { it.code < 128 && it.isLetterOrDigit() }.uppercase()
        if (t.length == 10 && t.startsWith("ASN")) t = t.substring(3)
        return if (t.length == 7) t else null
    }

    /** متن آماده برای گذاشتن در کانال/گروه (تلگرام، ایتا، بله، روبیکا و ...). */
    fun shareText(context: Context, title: String, organizer: String, code: String, tappable: Boolean = false): String {
        val base = context.getString(R.string.poll_link_base).trim()
        val sb = StringBuilder()
        sb.append("📊 نظرسنجی: ").append(title).append("\n")
        if (organizer.isNotBlank()) sb.append(organizer).append("\n")
        sb.append("\n")
        if (base.isNotBlank()) {
            sb.append("🔗 شرکت در نظرسنجی:\n")
            sb.append(base.trimEnd('/')).append("/?c=").append(code).append("\n\n")
        }
        if (tappable) {
            // کد داخل بک‌تیک: در تلگرام، ایتا و واتس‌اپ مونو‌اسپیس می‌شود و با یک لمس کپی می‌شود
            sb.append("👇 برای کپی، روی کد بزنید\n\n")
            sb.append("`ASN-").append(code).append("`\n\n")
        } else {
            // متن ساده: در همه‌ی شبکه‌ها (بله، روبیکا، اینستاگرام، پیامک و ...) بدون علامت اضافه نمایش داده می‌شود
            sb.append("👇 کد نظرسنجی (نگه دارید و کپی کنید)\n\n")
            sb.append("ASN-").append(code).append("\n\n")
        }
        sb.append("برنامه «آسان نت» ← نظرسنجی ← ورود با کد")
        return sb.toString()
    }

    fun shareLinkOnly(context: Context, code: String): String? {
        val base = context.getString(R.string.poll_link_base).trim()
        return if (base.isBlank()) null else base.trimEnd('/') + "/?c=" + code
    }
}

// ---------------------------------------------------------------- ابزارهای ساخت صفحه (بدون XML)

object PollUi {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)

    fun font(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.yekan_boom)

    private val STROKE = Color.parseColor("#33888888")

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Boolean = false): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        d.setColor(fill)
        if (stroke) d.setStroke(dp(ctx, 1), STROKE)
        return d
    }

    fun text(
        ctx: Context,
        value: String,
        sizeSp: Float = 15f,
        colorRes: Int = R.color.text_main,
        bold: Boolean = false
    ): TextView {
        val tv = TextView(ctx)
        tv.text = value
        tv.textSize = sizeSp
        tv.setTextColor(color(ctx, colorRes))
        font(ctx)?.let { tv.typeface = Typeface.create(it, if (bold) Typeface.BOLD else Typeface.NORMAL) }
        return tv
    }

    fun card(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 14)
        l.setPadding(p, p, p, p)
        l.background = rounded(ctx, color(ctx, R.color.surface), 14, stroke = true)
        return l
    }

    fun button(ctx: Context, label: String, filled: Boolean = true, onClick: () -> Unit): TextView {
        val b = text(
            ctx, label, 15f,
            if (filled) R.color.white else R.color.primary, bold = true
        )
        b.gravity = Gravity.CENTER
        val ph = dp(ctx, 18)
        val pv = dp(ctx, 12)
        b.setPadding(ph, pv, ph, pv)
        b.background = if (filled) rounded(ctx, color(ctx, R.color.primary), 14)
        else rounded(ctx, Color.TRANSPARENT, 14).also { it.setStroke(dp(ctx, 1), color(ctx, R.color.primary)) }
        b.setOnClickListener { onClick() }
        return b
    }

    fun lp(
        ctx: Context,
        topDp: Int = 0,
        w: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        h: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(ctx, topDp)
        return p
    }

    /** هدر بنفش بالای صفحه: فلش برگشت سمت چپ، عنوان سمت راست (مثل بقیه‌ی صفحه‌های برنامه). */
    fun header(activity: Activity, title: String): LinearLayout {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
        row.setBackgroundResource(R.drawable.header_gradient)
        row.setPadding(dp(activity, 16), dp(activity, 28), dp(activity, 20), dp(activity, 18))

        val back = ImageView(activity)
        back.setImageResource(R.drawable.ic_back)
        back.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
        back.setBackgroundResource(android.R.drawable.list_selector_background)
        back.setOnClickListener { activity.finish() }
        row.addView(back, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)))

        val t = text(activity, title, 22f, R.color.white, bold = true)
        t.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        t.textDirection = View.TEXT_DIRECTION_RTL
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
