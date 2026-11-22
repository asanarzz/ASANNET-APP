package com.kafinet.asannet

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

/** نمایش یک نظرسنجی: رأی دادن، دیدن نتیجه، و (برای صاحب نظرسنجی) اشتراک‌گذاری و مدیریت. */
class PollDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "poll_code"
        const val EXTRA_JUST_CREATED = "poll_just_created"
    }

    private lateinit var code: String
    private lateinit var box: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private var poll: PollDetail? = null
    private val selected = LinkedHashSet<String>()
    private var pendingCreatedDialog = false
    private var voting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = intent.getStringExtra(EXTRA_CODE).orEmpty().trim().uppercase()
        pendingCreatedDialog = intent.getBooleanExtra(EXTRA_JUST_CREATED, false)
        if (code.isBlank()) {
            finish()
            return
        }
        lifecycleScope.launch { SupabaseClient.logVisit(this@PollDetailActivity, "نظرسنجی") }
        buildUi()
        load()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(PollUi.color(this, R.color.bg))
        root.addView(PollUi.header(this, "نظرسنجی"))

        swipe = SwipeRefreshLayout(this)
        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        val pad = PollUi.dp(this, 14)
        scroll.setPadding(pad, pad, pad, PollUi.dp(this, 30))
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        swipe.addView(scroll)
        swipe.setOnRefreshListener { load() }
        root.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun load() {
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val r = PollsClient.getPoll(this@PollDetailActivity, code)
            swipe.isRefreshing = false
            when (r.status) {
                "ok" -> {
                    val p = r.poll!!
                    poll = p
                    if (p.voted || p.closed) selected.clear()
                    render(p)
                    if (pendingCreatedDialog) {
                        pendingCreatedDialog = false
                        showCreatedDialog(p)
                    }
                }
                "not_found" -> showMessage("این نظرسنجی پیدا نشد؛ ممکن است حذف شده باشد یا کد اشتباه باشد.", retry = false)
                else -> showMessage("اتصال برقرار نشد. اینترنت را بررسی کنید.", retry = true)
            }
        }
    }

    private fun showMessage(msg: String, retry: Boolean) {
        box.removeAllViews()
        val t = PollUi.text(this, msg, 16f, R.color.text_muted)
        t.gravity = Gravity.CENTER
        t.setPadding(PollUi.dp(this, 20), PollUi.dp(this, 50), PollUi.dp(this, 20), PollUi.dp(this, 16))
        box.addView(t)
        if (retry) box.addView(PollUi.button(this, "تلاش دوباره") { load() })
    }

    private fun render(p: PollDetail) {
        box.removeAllViews()

        // --- سرتیتر
        val head = PollUi.card(this)
        head.addView(PollUi.text(this, p.title, 19f, R.color.text_main, bold = true))
        if (p.organizer.isNotBlank()) {
            head.addView(PollUi.text(this, p.organizer, 13f, R.color.primary, bold = true), PollUi.lp(this, 4))
        }
        if (p.description.isNotBlank()) {
            head.addView(PollUi.text(this, p.description, 15f, R.color.text_main), PollUi.lp(this, 10))
        }
        val meta = ArrayList<String>()
        meta.add((if (p.closed) "⛔ " else "⏳ ") + PollFormat.timeLeft(p.endsAt, p.closed))
        val tv = p.totalVoters
        if (tv != null) meta.add("👥 " + PollFormat.digits(tv) + " نفر")
        head.addView(PollUi.text(this, meta.joinToString("      "), 13f, R.color.text_muted), PollUi.lp(this, 12))
        head.addView(PollUi.text(this, PollFormat.resultsLabel(p.showResults), 12f, R.color.text_muted), PollUi.lp(this, 4))
        box.addView(head)

        // --- گزینه‌ها
        val canVote = !p.closed && !p.voted
        val optionsCard = PollUi.card(this)
        if (canVote) {
            val hint = if (p.multiple) "می‌توانید چند گزینه را انتخاب کنید" else "یک گزینه را انتخاب کنید"
            optionsCard.addView(PollUi.text(this, hint, 13f, R.color.text_muted, bold = true))
        }
        for (o in p.options) optionsCard.addView(optionRow(p, o, canVote), PollUi.lp(this, 8))
        box.addView(optionsCard, PollUi.lp(this, 12))

        // --- وضعیت رأی
        if (canVote) {
            val btn = PollUi.button(this, "ثبت رأی") { submitVote(p) }
            btn.alpha = if (selected.isEmpty()) 0.5f else 1f
            box.addView(btn, PollUi.lp(this, 12))
        } else {
            val msg = when {
                p.voted && !p.canSeeResults -> "✅ رأی شما ثبت شد. نتیجه بعد از پایان نظرسنجی نمایش داده می‌شود."
                p.voted -> "✅ رأی شما ثبت شد."
                p.closed -> "این نظرسنجی بسته شده و دیگر نمی‌توان رأی داد."
                else -> ""
            }
            if (msg.isNotEmpty()) {
                val t = PollUi.text(this, msg, 14f, R.color.text_muted, bold = true)
                t.gravity = Gravity.CENTER
                box.addView(t, PollUi.lp(this, 14))
            }
        }

        // --- اشتراک‌گذاری و مدیریت
        val actions = PollUi.card(this)
        actions.addView(PollUi.button(this, "📤  اشتراک‌گذاری (همه‌ی شبکه‌ها)") { share(p) })
        actions.addView(
            PollUi.button(this, "📤  ارسال با کد قابل‌کپی (تلگرام، ایتا، واتس‌اپ)", filled = false) { share(p, true) },
            PollUi.lp(this, 8)
        )
        actions.addView(PollUi.button(this, "📋  کپی متن، لینک و کد", filled = false) { copyText(p) }, PollUi.lp(this, 8))
        if (p.isOwner) {
            if (!p.closed) {
                actions.addView(PollUi.button(this, "⛔  بستن نظرسنجی", filled = false) { confirmClose(p) }, PollUi.lp(this, 8))
            }
            val del = PollUi.text(this, "🗑  حذف نظرسنجی", 14f, R.color.danger, bold = true)
            del.gravity = Gravity.CENTER
            del.setPadding(0, PollUi.dp(this, 14), 0, PollUi.dp(this, 4))
            del.setOnClickListener { confirmDelete(p) }
            actions.addView(del)
        } else {
            val rep = PollUi.text(this, "گزارش نظرسنجی نامناسب", 13f, R.color.text_muted)
            rep.gravity = Gravity.CENTER
            rep.setPadding(0, PollUi.dp(this, 14), 0, PollUi.dp(this, 4))
            rep.setOnClickListener { reportDialog(p) }
            actions.addView(rep)
        }
        box.addView(actions, PollUi.lp(this, 14))
    }

    private fun optionRow(p: PollDetail, o: PollOption, canVote: Boolean): View {
        val isMine = p.myVotes.contains(o.id)
        val isSel = selected.contains(o.id)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        val pd = PollUi.dp(this, 10)
        row.setPadding(pd, pd, pd, pd)
        row.background = PollUi.rounded(
            this,
            if (isSel) Color.parseColor("#224F46E5") else Color.parseColor("#0F888888"),
            10
        )

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val mark = when {
            canVote -> if (p.multiple) (if (isSel) "☑" else "☐") else (if (isSel) "◉" else "○")
            isMine -> "✓"
            else -> ""
        }
        if (mark.isNotEmpty()) {
            val m = PollUi.text(this, mark, 20f, if (isSel || isMine) R.color.primary else R.color.text_muted, bold = true)
            m.setPadding(0, 0, PollUi.dp(this, 10), 0)
            top.addView(m)
        }
        top.addView(
            PollUi.text(this, o.label, 16f, R.color.text_main, bold = isSel || isMine),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val votes = o.votes
        var pct = 0
        if (votes != null) {
            val total = p.totalVoters ?: 0
            pct = if (total > 0) (votes * 100) / total else 0
            top.addView(
                PollUi.text(this, PollFormat.digits(votes) + " رأی • " + PollFormat.digits(pct) + "٪", 13f, R.color.text_muted)
            )
        }
        row.addView(top)

        if (votes != null) {
            val track = LinearLayout(this)
            track.orientation = LinearLayout.HORIZONTAL
            track.weightSum = 100f
            track.background = PollUi.rounded(this, Color.parseColor("#22888888"), 4)
            val fill = View(this)
            fill.background = PollUi.rounded(this, PollUi.color(this, R.color.primary), 4)
            track.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.toFloat()))
            row.addView(track, PollUi.lp(this, 8, ViewGroup.LayoutParams.MATCH_PARENT, PollUi.dp(this, 8)))
        }

        if (canVote) {
            row.setOnClickListener { toggle(p, o.id) }
        }
        return row
    }

    private fun toggle(p: PollDetail, optionId: String) {
        if (p.multiple) {
            if (!selected.remove(optionId)) selected.add(optionId)
        } else {
            selected.clear()
            selected.add(optionId)
        }
        render(p)
    }

    private fun submitVote(p: PollDetail) {
        if (voting) return
        if (selected.isEmpty()) {
            toast("اول یک گزینه را انتخاب کنید")
            return
        }
        voting = true
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val st = PollsClient.vote(this@PollDetailActivity, p.code, selected.toList())
            voting = false
            swipe.isRefreshing = false
            when (st) {
                "ok" -> toast("رأی شما ثبت شد ✅")
                "already" -> toast("شما قبلاً در این نظرسنجی رأی داده‌اید.")
                "closed" -> toast("این نظرسنجی بسته شده است.")
                "no_user" -> toast("ابتدا وارد حساب خود شوید.")
                "not_found" -> toast("نظرسنجی پیدا نشد.")
                else -> toast("ثبت رأی انجام نشد؛ اینترنت را بررسی کنید.")
            }
            if (st == "ok" || st == "already" || st == "closed") {
                selected.clear()
                load()
            }
        }
    }

    private fun share(p: PollDetail, tappable: Boolean = false) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, PollFormat.shareText(this, p.title, p.organizer, p.code, tappable))
        startActivity(Intent.createChooser(send, "ارسال نظرسنجی به ..."))
    }

    private fun copyText(p: PollDetail) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("poll", PollFormat.shareText(this, p.title, p.organizer, p.code)))
        toast("کپی شد؛ حالا در کانال یا گروه پیست کنید.")
    }

    private fun showCreatedDialog(p: PollDetail) {
        AlertDialog.Builder(this)
            .setTitle("نظرسنجی ساخته شد ✅")
            .setMessage(
                "حالا متن و لینک نظرسنجی را در کانال یا گروهتان بگذارید تا اعضا بتوانند رأی بدهند.\n\nکد: ASN-" + p.code
            )
            .setPositiveButton("اشتراک‌گذاری") { _, _ -> share(p) }
            .setNegativeButton("بعداً", null)
            .show()
    }

    private fun confirmClose(p: PollDetail) {
        AlertDialog.Builder(this)
            .setTitle("بستن نظرسنجی؟")
            .setMessage("بعد از بستن، کسی نمی‌تواند رأی بدهد و نتیجه برای همه نمایش داده می‌شود.")
            .setPositiveButton("بستن") { _, _ ->
                lifecycleScope.launch {
                    val ok = PollsClient.closePoll(this@PollDetailActivity, p.code)
                    toast(if (ok) "نظرسنجی بسته شد." else "انجام نشد؛ اینترنت را بررسی کنید.")
                    if (ok) load()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(p: PollDetail) {
        AlertDialog.Builder(this)
            .setTitle("حذف نظرسنجی؟")
            .setMessage("نظرسنجی و همه‌ی رأی‌ها برای همیشه پاک می‌شود.")
            .setPositiveButton("حذف") { _, _ ->
                lifecycleScope.launch {
                    val ok = PollsClient.deletePoll(this@PollDetailActivity, p.code)
                    if (ok) {
                        toast("نظرسنجی حذف شد.")
                        finish()
                    } else {
                        toast("انجام نشد؛ اینترنت را بررسی کنید.")
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun reportDialog(p: PollDetail) {
        val reasons = arrayOf("محتوای نامناسب یا توهین‌آمیز", "تبلیغ یا اسپم", "کلاهبرداری یا فریب", "دلیل دیگر")
        AlertDialog.Builder(this)
            .setTitle("گزارش نظرسنجی")
            .setItems(reasons) { _, which ->
                lifecycleScope.launch {
                    val st = PollsClient.report(this@PollDetailActivity, p.code, reasons[which])
                    when (st) {
                        "ok" -> toast("گزارش شما ثبت شد. ممنون.")
                        "already" -> toast("قبلاً این نظرسنجی را گزارش کرده‌اید.")
                        else -> toast("ثبت گزارش انجام نشد؛ اینترنت را بررسی کنید.")
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
