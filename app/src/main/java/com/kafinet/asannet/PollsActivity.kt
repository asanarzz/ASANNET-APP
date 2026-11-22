package com.kafinet.asannet

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

/** صفحه‌ی اصلی بخش نظرسنجی: «نظرسنجی‌های من»، «شرکت کرده‌ام»، ساخت نظرسنجی و ورود با کد. */
class PollsActivity : AppCompatActivity() {

    private var tab = 0 // 0 = نظرسنجی‌های من ، 1 = شرکت کرده‌ام
    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var tabMine: TextView
    private lateinit var tabJoined: TextView
    private var loadId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { SupabaseClient.logVisit(this@PollsActivity, "نظرسنجی") }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(PollUi.color(this, R.color.bg))
        root.addView(PollUi.header(this, "نظرسنجی"))

        // دکمه‌های بالا
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val pad = PollUi.dp(this, 14)
        actions.setPadding(pad, pad, pad, PollUi.dp(this, 6))
        val btnCreate = PollUi.button(this, "＋  ساخت نظرسنجی") {
            startActivity(Intent(this, CreatePollActivity::class.java))
        }
        val btnCode = PollUi.button(this, "ورود با کد", filled = false) { showCodeDialog() }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val half2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half2.marginEnd = PollUi.dp(this, 10)
        actions.addView(btnCreate, half2)
        actions.addView(btnCode, half)
        root.addView(actions)

        // تب‌ها
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(pad, PollUi.dp(this, 6), pad, PollUi.dp(this, 6))
        tabMine = tabView("نظرسنجی‌های من") { selectTab(0) }
        tabJoined = tabView("شرکت کرده‌ام") { selectTab(1) }
        val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val tp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp2.marginEnd = PollUi.dp(this, 10)
        tabs.addView(tabMine, tp2)
        tabs.addView(tabJoined, tp)
        root.addView(tabs)
        styleTabs()

        // لیست
        val frame = FrameLayout(this)
        swipe = SwipeRefreshLayout(this)
        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        scroll.setPadding(pad, PollUi.dp(this, 6), pad, PollUi.dp(this, 24))
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        scroll.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        swipe.addView(scroll)
        swipe.setOnRefreshListener { load() }
        frame.addView(swipe, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyText = PollUi.text(this, "", 16f, R.color.text_muted)
        emptyText.gravity = Gravity.CENTER
        emptyText.setPadding(PollUi.dp(this, 30), PollUi.dp(this, 30), PollUi.dp(this, 30), PollUi.dp(this, 30))
        emptyText.visibility = View.GONE
        frame.addView(
            emptyText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun tabView(label: String, onClick: () -> Unit): TextView {
        val t = PollUi.text(this, label, 14f, R.color.primary, bold = true)
        t.gravity = Gravity.CENTER
        val ph = PollUi.dp(this, 12)
        val pv = PollUi.dp(this, 10)
        t.setPadding(ph, pv, ph, pv)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleTabs() {
        styleTab(tabMine, tab == 0)
        styleTab(tabJoined, tab == 1)
    }

    private fun styleTab(t: TextView, selected: Boolean) {
        t.setTextColor(PollUi.color(this, if (selected) R.color.white else R.color.primary))
        t.background = PollUi.rounded(
            this,
            PollUi.color(this, if (selected) R.color.primary else R.color.surface),
            20,
            stroke = !selected
        )
    }

    private fun selectTab(index: Int) {
        if (tab == index) return
        tab = index
        styleTabs()
        listBox.removeAllViews()
        emptyText.visibility = View.GONE
        load()
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun load() {
        val myId = ++loadId
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val list = if (tab == 0) PollsClient.myPolls(this@PollsActivity) else PollsClient.participated(this@PollsActivity)
            if (myId != loadId) return@launch
            swipe.isRefreshing = false
            listBox.removeAllViews()
            if (list == null) {
                showEmpty("اتصال برقرار نشد.\nصفحه را به پایین بکشید تا دوباره تلاش شود.")
                return@launch
            }
            if (list.isEmpty()) {
                showEmpty(
                    if (tab == 0) "هنوز نظرسنجی نساخته‌اید.\nبا دکمه‌ی «ساخت نظرسنجی» اولین نظرسنجی را بسازید و لینکش را در کانال یا گروهتان بگذارید."
                    else "هنوز در هیچ نظرسنجی شرکت نکرده‌اید.\nاز لینکی که در کانال یا گروه است وارد شوید، یا «ورود با کد» را بزنید."
                )
                return@launch
            }
            emptyText.visibility = View.GONE
            for (p in list) listBox.addView(buildCard(p), PollUi.lp(this@PollsActivity, 10))
        }
    }

    private fun buildCard(p: PollSummary): View {
        val c = PollUi.card(this)
        c.addView(PollUi.text(this, p.title, 16f, R.color.text_main, bold = true))
        val parts = ArrayList<String>()
        if (tab == 0) {
            val n = p.totalVoters ?: 0
            parts.add(PollFormat.digits(n) + " رأی")
        }
        parts.add(PollFormat.timeLeft(p.endsAt, p.closed))
        if (tab == 0 && p.hidden) parts.add("پنهان‌شده توسط مدیر")
        c.addView(PollUi.text(this, parts.joinToString(" • "), 13f, R.color.text_muted), PollUi.lp(this, 6))
        if (tab == 0) {
            val code = PollUi.text(this, "کد: ASN-" + p.code, 12f, R.color.text_muted)
            code.textDirection = View.TEXT_DIRECTION_LTR
            code.gravity = Gravity.START
            c.addView(code, PollUi.lp(this, 4))
        }
        c.setOnClickListener { openPoll(p.code) }
        return c
    }

    private fun openPoll(code: String) {
        startActivity(Intent(this, PollDetailActivity::class.java).putExtra(PollDetailActivity.EXTRA_CODE, code))
    }

    private fun showCodeDialog() {
        val input = EditText(this)
        input.hint = "کد یا لینک نظرسنجی"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine()
        input.gravity = Gravity.CENTER
        input.textDirection = View.TEXT_DIRECTION_LTR
        PollUi.font(this)?.let { input.typeface = it }

        // اگر کاربر از صفحه‌ی وب «کپی کد» زده باشد، همین‌جا خودکار پر می‌شود
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            val looksLikePoll = text.contains("ASN-", ignoreCase = true) || text.contains("c=") || text.contains("poll/")
            if (looksLikePoll) {
                PollFormat.extractCode(text)?.let { input.setText("ASN-$it") }
            }
        } catch (e: Exception) {
            // کلیپ‌بورد در دسترس نبود؛ مشکلی نیست
        }

        val box = FrameLayout(this)
        val p = PollUi.dp(this, 22)
        box.setPadding(p, PollUi.dp(this, 10), p, 0)
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("ورود با کد نظرسنجی")
            .setMessage("کدی که مدیر کانال یا گروه فرستاده را وارد کنید (مثلاً ASN-K7M2QXA).")
            .setView(box)
            .setPositiveButton("باز کردن") { _, _ ->
                val code = PollFormat.extractCode(input.text.toString())
                if (code == null) {
                    Toast.makeText(this, "کد معتبر نیست", Toast.LENGTH_SHORT).show()
                } else {
                    openPoll(code)
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}

/**
 * باز شدن لینک نظرسنجی (asannet://poll/KODE) از تلگرام، ایتا، مرورگر و ...
 * اگر کاربر هنوز وارد برنامه نشده، کد نگه داشته می‌شود و بعد از ثبت‌نام/ورود همان نظرسنجی باز می‌شود.
 */
class PollLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = PollFormat.extractCode(intent?.dataString.orEmpty())
        if (code == null) {
            Toast.makeText(this, "لینک نظرسنجی معتبر نیست", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (SessionManager.isRegistered(this)) {
            val main = Intent(this, MainActivity::class.java)
            val detail = Intent(this, PollDetailActivity::class.java)
                .putExtra(PollDetailActivity.EXTRA_CODE, code)
            startActivities(arrayOf(main, detail))
        } else {
            PollsClient.setPendingCode(this, code)
            val reg = Intent(this, RegistrationActivity::class.java)
            reg.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(reg)
        }
        finish()
    }
}
