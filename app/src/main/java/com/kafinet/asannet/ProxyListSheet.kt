package com.kafinet.asannet

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * پنجره‌ی پایین‌کشویی «اتصال به پروکسی» — شبیه تلگرام: لیست پروکسی‌ها با پینگ، با یک لمس وصل می‌شود.
 * ساخته‌شدن کل ظاهر با کد است تا به فایل XML جدیدی نیاز نباشد.
 */
class ProxyListSheet(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit
) {

    private val cPrimary = Color.parseColor("#4F46E5")
    private val cText = Color.parseColor("#1E1B2E")
    private val cMuted = Color.parseColor("#6B7280")
    private val cGreen = Color.parseColor("#16A34A")
    private val cOrange = Color.parseColor("#D97706")
    private val cRed = Color.parseColor("#DC2626")
    private val cBorder = Color.parseColor("#E5E7EB")

    private lateinit var dialog: BottomSheetDialog
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var disconnectBtn: TextView
    private lateinit var countText: TextView
    private lateinit var deadToggle: TextView
    private var showDead = false

    companion object {
        // حداکثر تعداد ردیفی که همزمان رسم می‌شود (بقیه پشت صحنه تست می‌شوند و بعد از پنهان شدن خراب‌ها دیده می‌شوند)
        private const val MAX_ROWS = 150
    }
    private lateinit var clearCustomLink: TextView

    private var items: List<ProxyEntry> = emptyList()
    private val pings = HashMap<String, Long>()      // پینگ به میلی‌ثانیه؛ ‎-1 یعنی کار نمی‌کند
    private val testing = HashSet<String>()
    private var testJob: Job? = null
    private var loadingList = false

    // فهرست شناسه‌ها را یک‌بار می‌خوانیم؛ قبلاً برای هر ردیف و در هر بار رسم، فایل ذخیره‌شده دوباره
    // خوانده و تجزیه می‌شد و با ۲۰۰ پروکسی صفحه را قفل می‌کرد.
    private var customIds: Set<String> = emptySet()
    private var cachedIds: Set<String> = emptySet()

    private fun reloadSets() {
        customIds = ProxyRepository.custom(activity).map { it.id }.toSet()
        cachedIds = ProxyRepository.cached(activity).map { it.id }.toSet()
    }

    private fun isCustom(p: ProxyEntry): Boolean = p.id in customIds && p.id !in cachedIds

    // رسم دوباره‌ی لیست را در حین تست حداکثر هر ۳۰۰ میلی‌ثانیه یک‌بار انجام می‌دهیم
    private val uiHandler = Handler(Looper.getMainLooper())
    private var renderPending = false

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        uiHandler.postDelayed({
            renderPending = false
            if (dialog.isShowing) render()
        }, 300)
    }

    fun show() {
        dialog = BottomSheetDialog(activity)
        dialog.setContentView(buildContent())
        dialog.setOnDismissListener {
            testJob?.cancel()
            testing.clear()
            onChanged()
        }
        reloadSets()
        items = ProxyRepository.all(activity)
        items.forEach { p -> ProxyManager.cachedPing(p.id)?.let { pings[p.id] = it } }
        render()
        dialog.show()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        refresh()
    }

    // ------------------------------------------------------------------ ساخت ظاهر

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(18), dp(16), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(text("اتصال به پروکسی", 19f, cText, bold = true))

        statusText = text("", 13f, cMuted).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(statusText)

        // شمارنده: تعداد کل، سالم، خراب و آن‌هایی که هنوز در حال تست‌اند
        countText = text("", 12f, cMuted, bold = true).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(countText)

        // پروکسی‌های خراب به‌طور پیش‌فرض پنهان‌اند تا لیست سبک بماند
        deadToggle = text("", 12f, cPrimary, bold = true).apply {
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
            setOnClickListener { showDead = !showDead; render() }
        }
        root.addView(deadToggle)

        root.addView(text(
            "یکی از پروکسی‌ها را لمس کن تا وصل شود. وقتی وصل باشی، همه‌ی برنامه (از جمله لینک‌هایی مثل اینستاگرام) از سد فیلترینگ رد می‌شود.",
            12f, cMuted
        ).apply { setPadding(0, dp(6), 0, dp(10)) })

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        disconnectBtn = pill("قطع اتصال", filled = false, color = cRed) {
            ProxyManager.setActive(activity, null)
            toast("پروکسی قطع شد.")
            updateStatus(); render(); onChanged()
        }
        buttons.addView(pill("به‌روزرسانی و تست", filled = true, color = cPrimary) { refresh(force = true) }, pillLp())
        buttons.addView(pill("افزودن دستی", filled = false, color = cPrimary) { showAddDialog() }, pillLp())
        buttons.addView(disconnectBtn, pillLp())
        root.addView(buttons)

        clearCustomLink = text("حذف همه‌ی پروکسی‌هایی که خودم اضافه کرده‌ام", 12f, cRed, bold = true).apply {
            setPadding(0, dp(12), 0, dp(2))
            setOnClickListener { confirmClearCustom() }
        }
        root.addView(clearCustomLink)

        listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(activity).apply { addView(listBox) }
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH).apply {
            topMargin = dp(12)
        })

        updateStatus()
        return root
    }

    private fun updateStatus() {
        val a = ProxyManager.active
        when {
            ProxyManager.connecting -> {
                statusText.text = "در حال اتصال…"
                statusText.setTextColor(cOrange)
            }
            a != null -> {
                statusText.text = "وصل به: ${a.name}  (${a.typeLabel})"
                statusText.setTextColor(cGreen)
            }
            else -> {
                statusText.text = "قطع است"
                statusText.setTextColor(cMuted)
            }
        }
        disconnectBtn.visibility = if (a != null) View.VISIBLE else View.GONE
    }

    private fun updateCounts() {
        if (!::countText.isInitialized) return
        val total = items.size
        if (total == 0) {
            countText.text = ""
            deadToggle.visibility = View.GONE
            return
        }
        var ok = 0
        var dead = 0
        items.forEach { p ->
            val v = pings[p.id]
            if (v != null && v > 0) ok++ else if (v != null && v < 0) dead++
        }
        val inTest = items.count { it.id in testing }
        val sb = StringBuilder("کل: $total   •   سالم: $ok   •   خراب: $dead")
        if (inTest > 0) {
            sb.append("   •   در حال تست: $inTest")
            countText.setTextColor(cOrange)
        } else {
            sb.append("   •   تست تمام شد ✓")
            countText.setTextColor(cMuted)
        }
        countText.text = sb.toString()
        deadToggle.visibility = if (dead > 0) View.VISIBLE else View.GONE
        deadToggle.text = if (showDead) "پنهان کردن پروکسی‌های خراب" else "نمایش پروکسی‌های خراب ($dead)"
    }

    private fun render() {
        if (!::listBox.isInitialized) return
        updateCounts()
        listBox.removeAllViews()
        if (::clearCustomLink.isInitialized) {
            clearCustomLink.visibility =
                if (customIds.isNotEmpty()) View.VISIBLE else View.GONE
        }

        val visible = if (showDead) items else items.filter { (pings[it.id] ?: 0L) >= 0L }
        if (visible.isEmpty()) {
            val msg = when {
                loadingList -> "در حال دریافت لیست…"
                items.isEmpty() -> "هنوز پروکسی‌ای در لیست نیست.\nاگر پروکسی داری، با «افزودن دستی» اضافه‌اش کن، یا از مدیر بخواه لیست را در پنل اضافه کند."
                testing.isNotEmpty() -> "در حال تست پروکسی‌ها…"
                else -> "هیچ پروکسی سالمی پیدا نشد.\n«نمایش پروکسی‌های خراب» را بزن یا کمی بعد دوباره «به‌روزرسانی و تست» کن."
            }
            listBox.addView(text(msg, 13f, cMuted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
            return
        }

        // کارکرده‌ها (با کمترین پینگ) → تست‌نشده‌ها → خراب‌ها
        val sorted = visible.sortedWith(compareBy<ProxyEntry> {
            val p = pings[it.id]
            when {
                p == null -> 1
                p > 0 -> 0
                else -> 2
            }
        }.thenBy { pings[it.id]?.takeIf { v -> v > 0 } ?: Long.MAX_VALUE })

        val active = ProxyManager.active
        val shown = sorted.take(MAX_ROWS)
        shown.forEach { p ->
            val isActive = active?.id == p.id
            val ping = pings[p.id]
            val dead = ping != null && ping < 0

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                alpha = if (dead) 0.55f else 1f
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isActive) Color.parseColor("#ECFDF5") else Color.WHITE)
                    setStroke(dp(1), if (isActive) cGreen else cBorder)
                }
            }

            val nameCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            nameCol.addView(text(p.name, 14f, cText, bold = true).apply { maxLines = 1 })
            val sub = buildString {
                append(p.typeLabel)
                if (isCustom(p)) append("  •  دستی")
                if (!p.user.isNullOrBlank()) append("  •  رمزدار")
            }
            nameCol.addView(text(sub, 11f, cMuted))
            row.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val (label, color) = when {
                isActive -> "✓ وصل است" to cGreen
                p.id in testing -> "در حال تست…" to cMuted
                ping == null -> "—" to cMuted
                ping < 0 -> "کار نمی‌کند" to cRed
                ping < 1500 -> "$ping ms" to cGreen
                else -> "$ping ms" to cOrange
            }
            row.addView(text(label, 12f, color, bold = true))

            // پروکسی‌هایی که خود کاربر اضافه کرده، دکمه‌ی حذف جدا دارند
            if (isCustom(p)) {
                row.addView(text("حذف", 12f, cRed, bold = true).apply {
                    setPadding(dp(14), dp(8), dp(2), dp(8))
                    setOnClickListener { confirmDelete(p) }
                })
            }

            row.setOnClickListener { connect(p) }
            row.setOnLongClickListener {
                if (isCustom(p)) confirmDelete(p)
                true
            }
            listBox.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        if (sorted.size > shown.size) {
            listBox.addView(text(
                "و ${sorted.size - shown.size} پروکسی دیگر (بعد از پایان تست و پنهان شدن خراب‌ها دیده می‌شوند)",
                12f, cMuted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(16))
            })
        }
    }

    // ------------------------------------------------------------------ کارها

    private fun connect(p: ProxyEntry) {
        if (ProxyManager.connecting) return
        ProxyManager.connecting = true
        updateStatus(); onChanged()
        activity.lifecycleScope.launch {
            val ms = ProxyManager.test(p, 8000)
            ProxyManager.connecting = false
            if (ms != null) {
                ProxyManager.setActive(activity, p)
                pings[p.id] = ms
                toast("وصل شد ✓  همه‌ی برنامه حالا از پروکسی رد می‌شود.")
            } else {
                pings[p.id] = -1L
                toast("این پروکسی جواب نمی‌دهد؛ یکی دیگر را امتحان کن.")
            }
            if (dialog.isShowing) { updateStatus(); render() }
            onChanged()
        }
    }

    /** لیست را از سرور می‌گیرد و بعد همه را تست می‌کند. */
    private fun refresh(force: Boolean = false) {
        if (loadingList) return
        loadingList = true
        render()
        activity.lifecycleScope.launch {
            val remote = ProxyRepository.fetchRemote(activity)
            loadingList = false
            if (!dialog.isShowing) return@launch
            reloadSets()
            items = ProxyRepository.all(activity)
            if (remote == null && items.isNotEmpty()) {
                toast("به سرور وصل نشدم؛ لیست ذخیره‌شده‌ی قبلی را می‌بینی.")
            }
            render()
            testAll(force)
        }
    }

    /** force=false: فقط پروکسی‌هایی که در ۱۰ دقیقه‌ی اخیر تست نشده‌اند تست می‌شوند. */
    private fun testAll(force: Boolean = true) {
        testJob?.cancel()
        val targets = items.filter { force || ProxyManager.cachedPing(it.id) == null }
        if (targets.isEmpty()) return
        targets.forEach { pings.remove(it.id); testing.add(it.id) }
        render()
        val finished = java.util.concurrent.ConcurrentHashMap<String, Long>()
        testJob = activity.lifecycleScope.launch {
            try {
                val sem = Semaphore(40)
                targets.map { p ->
                    async {
                        sem.withPermit {
                            val ms = ProxyManager.test(p, 4500, quick = true, strict = true)
                            pings[p.id] = ms ?: -1L
                            finished[p.id] = ms ?: -1L
                            testing.remove(p.id)
                            scheduleRender()
                        }
                    }
                }.awaitAll()
            } finally {
                testing.clear()
                scheduleRender()
                // نتیجه‌ی تست پروکسی‌های لیست سرور را همان لحظه برای مدیر می‌فرستد (حتی اگر پنجره وسط تست بسته شده باشد)
                val toReport = finished.filterKeys { it in cachedIds }.map { Pair(it.key, it.value) }
                if (toReport.isNotEmpty()) {
                    val app = activity.applicationContext
                    CoroutineScope(Dispatchers.IO).launch { ProxyRepository.reportResults(app, toReport) }
                }
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(activity).apply {
            hint = "socks5://host:port\nیا لینک tg://socks…\nیا host:port\n(هر خط یک پروکسی)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("افزودن پروکسی")
            .setView(input)
            .setPositiveButton("افزودن") { _, _ ->
                val lines = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val ok = ArrayList<ProxyEntry>()
                var mtproto = 0
                var bad = 0
                lines.forEach { line ->
                    val e = ProxyManager.parse(line)
                    when {
                        e != null -> ok.add(e)
                        Regex("^(tg://|https?://t\\.me/)proxy", RegexOption.IGNORE_CASE).containsMatchIn(line) -> mtproto++
                        else -> bad++
                    }
                }
                if (ok.isNotEmpty()) {
                    ProxyRepository.addCustom(activity, ok)
                    reloadSets()
                    items = ProxyRepository.all(activity)
                    testAll()
                }
                val msg = buildString {
                    append("${ok.size} پروکسی اضافه شد.")
                    if (mtproto > 0) append(" (لینک‌های MTProto مخصوص خود تلگرام‌اند و اینجا کار نمی‌کنند: $mtproto)")
                    if (bad > 0) append(" (نامعتبر: $bad)")
                }
                toast(msg)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmClearCustom() {
        AlertDialog.Builder(activity)
            .setMessage("همه‌ی پروکسی‌هایی که خودت اضافه کرده‌ای حذف شود؟ (لیست اصلی برنامه دست نمی‌خورد)")
            .setPositiveButton("حذف همه") { _, _ ->
                val a = ProxyManager.active
                if (a != null && isCustom(a)) ProxyManager.setActive(activity, null)
                testJob?.cancel()
                testing.clear()
                ProxyRepository.clearCustom(activity)
                reloadSets()
                items = ProxyRepository.all(activity)
                updateStatus(); render(); onChanged()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(p: ProxyEntry) {
        AlertDialog.Builder(activity)
            .setMessage("«${p.name}» از لیست حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                if (ProxyManager.active?.id == p.id) ProxyManager.setActive(activity, null)
                ProxyRepository.removeCustom(activity, p)
                reloadSets()
                items = ProxyRepository.all(activity)
                updateStatus(); render(); onChanged()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ------------------------------------------------------------------ ابزارهای ظاهر

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(activity).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pillLp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(6)
    }

    private fun pill(label: String, filled: Boolean, color: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) Color.WHITE else color)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (filled) setColor(color) else { setColor(Color.WHITE); setStroke(dp(1), color) }
            }
            setOnClickListener { onClick() }
        }
}
