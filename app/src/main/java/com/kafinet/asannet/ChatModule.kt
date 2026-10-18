package com.kafinet.asannet

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ItemChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val id: Long,
    val isMine: Boolean,
    val body: String,
    val createdAt: String
)

data class ChatSummary(
    val chatId: String,
    val adId: String,
    val adTitle: String,
    val adImage: String,
    val otherName: String,
    val myRole: String,
    val lastBody: String,
    val lastAt: String,
    val unread: Int
)

class ChatStartResult(val chatId: String?, val message: String)
class MessagesResult(val messages: List<ChatMessage>, val error: Boolean)
class InboxResult(val chats: List<ChatSummary>, val error: Boolean)

private fun chatDp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/** ارتباط با توابع چت در Supabase. هویت هر کاربر همان کد تصادفی روی گوشی اوست (AdsClient.ownerToken). */
object ChatClient {

    /** گفتگو را شروع می‌کند (یا اگر قبلاً بوده همان را برمی‌گرداند). اگر نشد، دلیلش را هم می‌دهد. */
    suspend fun start(context: Context, adId: String): ChatStartResult = withContext(Dispatchers.IO) {
        try {
            val conn = AdsClient.openConn(context, "/rest/v1/rpc/chat_start", "POST")
            val body = JSONObject()
            body.put("p_ad_id", adId)
            body.put("p_token", AdsClient.ownerToken(context))
            body.put("p_name", SessionManager.getFullName(context) ?: "کاربر آسان نت")
            AdsClient.writeJson(conn, body)
            val code = conn.responseCode
            val text = AdsClient.readBody(conn)
            conn.disconnect()
            if (code !in 200..299) {
                val msg = when {
                    text.contains("own_ad") ->
                        "این آگهی مال خودتان است و نمی‌توانید با خودتان چت کنید"
                    text.contains("chat_not_allowed") ->
                        "فروشنده چت را برای این آگهی فعال نکرده است"
                    text.contains("daily_limit") ->
                        "امروز تعداد زیادی گفتگو شروع کرده‌اید. فردا دوباره امتحان کنید"
                    code == 404 || text.contains("Could not find the function") ->
                        "چت هنوز در سوپابیس فعال نشده (فایل ads_update_1.sql اجرا نشده)"
                    else -> "باز کردن گفتگو ناموفق بود (کد $code)"
                }
                return@withContext ChatStartResult(null, msg)
            }
            val id = text.trim().trim('"')
            if (id.isBlank()) ChatStartResult(null, "پاسخ نامعتبر از سرور") else ChatStartResult(id, "")
        } catch (e: Exception) {
            ChatStartResult(null, "اتصال به اینترنت برقرار نشد")
        }
    }

    suspend fun send(context: Context, chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = AdsClient.openConn(context, "/rest/v1/rpc/chat_send", "POST")
            val body = JSONObject()
            body.put("p_chat_id", chatId)
            body.put("p_token", AdsClient.ownerToken(context))
            body.put("p_body", text)
            AdsClient.writeJson(conn, body)
            val code = conn.responseCode
            val resp = AdsClient.readBody(conn)
            conn.disconnect()
            code in 200..299 && resp.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun messages(context: Context, chatId: String, afterId: Long): MessagesResult =
        withContext(Dispatchers.IO) {
            try {
                val conn = AdsClient.openConn(context, "/rest/v1/rpc/chat_messages", "POST")
                val body = JSONObject()
                body.put("p_chat_id", chatId)
                body.put("p_token", AdsClient.ownerToken(context))
                body.put("p_after", afterId)
                AdsClient.writeJson(conn, body)
                val code = conn.responseCode
                val text = AdsClient.readBody(conn)
                conn.disconnect()
                if (code !in 200..299) return@withContext MessagesResult(emptyList(), true)
                val arr = JSONArray(text)
                val out = ArrayList<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        ChatMessage(
                            id = o.optLong("id"),
                            isMine = o.optBoolean("is_mine", false),
                            body = o.optString("body"),
                            createdAt = o.optString("created_at")
                        )
                    )
                }
                MessagesResult(out, false)
            } catch (e: Exception) {
                MessagesResult(emptyList(), true)
            }
        }

    suspend fun inbox(context: Context): InboxResult = withContext(Dispatchers.IO) {
        try {
            val conn = AdsClient.openConn(context, "/rest/v1/rpc/chat_inbox", "POST")
            AdsClient.writeJson(conn, JSONObject().put("p_token", AdsClient.ownerToken(context)))
            val code = conn.responseCode
            val text = AdsClient.readBody(conn)
            conn.disconnect()
            if (code !in 200..299) return@withContext InboxResult(emptyList(), true)
            val arr = JSONArray(text)
            val out = ArrayList<ChatSummary>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ChatSummary(
                        chatId = o.optString("chat_id"),
                        adId = o.optString("ad_id"),
                        adTitle = o.optString("ad_title"),
                        adImage = o.optString("ad_image"),
                        otherName = o.optString("other_name"),
                        myRole = o.optString("my_role"),
                        lastBody = o.optString("last_body"),
                        lastAt = o.optString("last_at"),
                        unread = o.optInt("unread", 0)
                    )
                )
            }
            InboxResult(out, false)
        } catch (e: Exception) {
            InboxResult(emptyList(), true)
        }
    }

    suspend fun unread(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val conn = AdsClient.openConn(context, "/rest/v1/rpc/chat_unread", "POST")
            AdsClient.writeJson(conn, JSONObject().put("p_token", AdsClient.ownerToken(context)))
            val code = conn.responseCode
            val text = AdsClient.readBody(conn)
            conn.disconnect()
            if (code !in 200..299) 0 else (text.trim().toIntOrNull() ?: 0)
        } catch (e: Exception) {
            0
        }
    }
}

/** حباب‌های پیام (با کد ساخته می‌شوند، پس layout جدا ندارند). */
class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<ChatMessage>()

    class VH(val frame: FrameLayout, val text: TextView) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val frame = FrameLayout(ctx)
        frame.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        frame.setPadding(chatDp(ctx, 10), chatDp(ctx, 4), chatDp(ctx, 10), chatDp(ctx, 4))

        val tv = TextView(ctx)
        tv.textSize = 16f
        tv.setLineSpacing(chatDp(ctx, 2).toFloat(), 1f)
        tv.setPadding(chatDp(ctx, 14), chatDp(ctx, 10), chatDp(ctx, 14), chatDp(ctx, 10))
        tv.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()
        frame.addView(
            tv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return VH(frame, tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.text.context
        holder.text.text = m.body
        val lp = holder.text.layoutParams as FrameLayout.LayoutParams
        if (m.isMine) {
            lp.gravity = Gravity.START
            holder.text.setBackgroundResource(R.drawable.chat_bubble_mine)
            holder.text.setTextColor(ContextCompat.getColor(ctx, R.color.white))
        } else {
            lp.gravity = Gravity.END
            holder.text.setBackgroundResource(R.drawable.chat_bubble_other)
            holder.text.setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
        }
        holder.text.layoutParams = lp
    }

    override fun getItemCount(): Int = items.size

    fun add(newOnes: List<ChatMessage>) {
        val start = items.size
        items.addAll(newOnes)
        notifyItemRangeInserted(start, newOnes.size)
    }

    fun lastId(): Long = if (items.isEmpty()) 0L else items[items.size - 1].id
}

/** لیست گفتگوها (صندوق پیام‌ها). */
class ChatListAdapter(
    private var items: List<ChatSummary>,
    private val onClick: (ChatSummary) -> Unit
) : RecyclerView.Adapter<ChatListAdapter.VH>() {

    class VH(val b: ItemChatBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.b.txtTitle.text = c.adTitle
        val role = if (c.myRole == "buyer") "فروشنده" else "خریدار"
        val name = if (c.otherName.isBlank()) "کاربر آسان نت" else c.otherName
        holder.b.txtWho.text = "$role: $name"
        holder.b.txtLast.text = c.lastBody
        if (c.unread > 0) {
            holder.b.txtUnread.visibility = android.view.View.VISIBLE
            holder.b.txtUnread.text = c.unread.toString()
        } else {
            holder.b.txtUnread.visibility = android.view.View.GONE
        }
        if (c.adImage.isNotBlank()) {
            Glide.with(holder.b.imgAd.context).load(c.adImage).centerCrop().into(holder.b.imgAd)
        } else {
            Glide.with(holder.b.imgAd.context).clear(holder.b.imgAd)
            holder.b.imgAd.setImageDrawable(null)
        }
        holder.b.root.setOnClickListener { onClick(c) }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<ChatSummary>) {
        items = newItems
        notifyDataSetChanged()
    }
}
