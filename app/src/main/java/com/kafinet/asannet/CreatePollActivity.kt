package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** فرم ساخت نظرسنجی توسط مدیر کانال/گروه. */
class CreatePollActivity : AppCompatActivity() {

    private companion object {
        const val MAX_OPTIONS = 10
    }

    private lateinit var etOrganizer: EditText
    private lateinit var etTitle: EditText
    private lateinit var etDescription: EditText
    private lateinit var optionsBox: LinearLayout
    private lateinit var btnAddOption: TextView
    private lateinit var switchMultiple: SwitchCompat
    private lateinit var btnCreate: TextView
    private lateinit var progress: ProgressBar
    private val optionInputs = ArrayList<EditText>()

    private var showResults = "after_vote"
    private var durationHours = 168 // ۷ روز؛ ۰ یعنی بدون مهلت
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(PollUi.color(this, R.color.bg))
        root.addView(PollUi.header(this, "ساخت نظرسنجی"))

        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        val pad = PollUi.dp(this, 16)
        scroll.setPadding(pad, pad, pad, PollUi.dp(this, 30))
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        scroll.addView(form, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // برگزارکننده
        form.addView(label("نام کانال/گروه یا برگزارکننده (اختیاری)"))
        etOrganizer = input("مثلاً: کانال خبری محله", 1, 80)
        form.addView(etOrganizer, PollUi.lp(this, 6))

        // عنوان
        form.addView(label("سؤال نظرسنجی"), PollUi.lp(this, 16))
        etTitle = input("سؤال خود را بنویسید", 1, 200)
        form.addView(etTitle, PollUi.lp(this, 6))

        // توضیح
        form.addView(label("توضیحات (اختیاری)"), PollUi.lp(this, 16))
        etDescription = input("توضیح کوتاه برای شرکت‌کنندگان", 3, 1000)
        form.addView(etDescription, PollUi.lp(this, 6))

        // گزینه‌ها
        form.addView(label("گزینه‌ها (حداقل ۲، حداکثر ۱۰)"), PollUi.lp(this, 16))
        optionsBox = LinearLayout(this)
        optionsBox.orientation = LinearLayout.VERTICAL
        form.addView(optionsBox, PollUi.lp(this, 0))
        addOptionRow(false)
        addOptionRow(false)
        btnAddOption = PollUi.button(this, "＋  افزودن گزینه", filled = false) { addOptionRow(true) }
        form.addView(btnAddOption, PollUi.lp(this, 10))

        // چندگزینه‌ای
        switchMultiple = SwitchCompat(this)
        switchMultiple.text = "انتخاب چند گزینه‌ای (هر نفر چند گزینه بتواند انتخاب کند)"
        switchMultiple.setTextColor(PollUi.color(this, R.color.text_main))
        PollUi.font(this)?.let { switchMultiple.typeface = it }
        switchMultiple.textSize = 14f
        form.addView(switchMultiple, PollUi.lp(this, 18))

        // نمایش نتیجه
        form.addView(label("نمایش نتیجه"), PollUi.lp(this, 18))
        form.addView(
            chipRow(
                listOf(
                    "after_vote" to "بعد از رأی دادن",
                    "always" to "همیشه",
                    "after_end" to "بعد از پایان"
                ),
                showResults
            ) { showResults = it },
            PollUi.lp(this, 8)
        )

        // مهلت
        form.addView(label("مهلت رأی‌گیری"), PollUi.lp(this, 18))
        form.addView(
            chipRow(
                listOf(
                    "24" to "۱ روز",
                    "72" to "۳ روز",
                    "168" to "۷ روز",
                    "720" to "۳۰ روز",
                    "0" to "بدون مهلت"
                ),
                durationHours.toString()
            ) { durationHours = it.toInt() },
            PollUi.lp(this, 8)
        )

        progress = ProgressBar(this)
        progress.visibility = View.GONE
        form.addView(progress, PollUi.lp(this, 16, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
        })

        btnCreate = PollUi.button(this, "ساخت نظرسنجی") { submit() }
        form.addView(btnCreate, PollUi.lp(this, 22))

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun label(text: String): TextView =
        PollUi.text(this, text, 13f, R.color.text_muted, bold = true)

    private fun input(hint: String, lines: Int, maxLen: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(PollUi.color(this, R.color.text_muted))
        e.setTextColor(PollUi.color(this, R.color.text_main))
        e.textSize = 16f
        PollUi.font(this)?.let { e.typeface = it }
        e.setBackgroundResource(R.drawable.ads_input_bg)
        val p = PollUi.dp(this, 12)
        e.setPadding(p, p, p, p)
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        if (lines > 1) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.setSingleLine()
        }
        return e
    }

    private fun addOptionRow(canRemove: Boolean) {
        if (optionInputs.size >= MAX_OPTIONS) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val et = input("", 1, 120)
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        optionInputs.add(et)
        if (canRemove) {
            val x = PollUi.text(this, "✕", 18f, R.color.danger, bold = true)
            x.setPadding(PollUi.dp(this, 12), PollUi.dp(this, 8), PollUi.dp(this, 4), PollUi.dp(this, 8))
            x.setOnClickListener {
                optionsBox.removeView(row)
                optionInputs.remove(et)
                refreshOptionHints()
            }
            row.addView(x)
        }
        optionsBox.addView(row, PollUi.lp(this, 8))
        refreshOptionHints()
        if (canRemove) et.requestFocus()
    }

    private fun refreshOptionHints() {
        for ((i, e) in optionInputs.withIndex()) {
            e.hint = "گزینه " + PollFormat.digits(i + 1)
        }
        if (::btnAddOption.isInitialized) {
            btnAddOption.visibility = if (optionInputs.size >= MAX_OPTIONS) View.GONE else View.VISIBLE
        }
    }

    private fun chipRow(items: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit): View {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<Pair<String, TextView>>()
        fun refresh(sel: String) {
            for ((k, tv) in chips) styleChip(tv, k == sel)
        }
        for ((key, label) in items) {
            val tv = PollUi.text(this, label, 14f, R.color.primary, bold = true)
            tv.setPadding(PollUi.dp(this, 14), PollUi.dp(this, 8), PollUi.dp(this, 14), PollUi.dp(this, 8))
            tv.setOnClickListener {
                refresh(key)
                onSelect(key)
            }
            chips.add(key to tv)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = PollUi.dp(this, 8)
            row.addView(tv, lp)
        }
        refresh(selectedKey)
        scroll.addView(row)
        return scroll
    }

    private fun styleChip(tv: TextView, selected: Boolean) {
        tv.setTextColor(PollUi.color(this, if (selected) R.color.white else R.color.primary))
        tv.background = PollUi.rounded(
            this,
            PollUi.color(this, if (selected) R.color.primary else R.color.surface),
            20,
            stroke = !selected
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnCreate.alpha = if (value) 0.5f else 1f
    }

    private fun submit() {
        if (busy) return
        val title = etTitle.text.toString().trim()
        val options = optionInputs.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        if (title.length < 3) {
            toast("سؤال نظرسنجی را بنویسید (حداقل ۳ حرف)")
            return
        }
        if (options.size < 2) {
            toast("حداقل ۲ گزینه لازم است")
            return
        }
        if (options.map { it.lowercase() }.toSet().size != options.size) {
            toast("گزینه‌ها نباید تکراری باشند")
            return
        }
        val endsAt = if (durationHours > 0) PollFormat.isoFromNow(durationHours) else null
        setBusy(true)
        lifecycleScope.launch {
            val r = PollsClient.createPoll(
                this@CreatePollActivity,
                etOrganizer.text.toString().trim(),
                title,
                etDescription.text.toString().trim(),
                options,
                switchMultiple.isChecked,
                showResults,
                endsAt
            )
            setBusy(false)
            val code = r.code
            if (code != null) {
                val intent = Intent(this@CreatePollActivity, PollDetailActivity::class.java)
                intent.putExtra(PollDetailActivity.EXTRA_CODE, code)
                intent.putExtra(PollDetailActivity.EXTRA_JUST_CREATED, true)
                startActivity(intent)
                finish()
            } else {
                when (r.error) {
                    "limit" -> toast("امروز بیش از حد نظرسنجی ساخته‌اید؛ فردا دوباره تلاش کنید.")
                    "no_user" -> toast("ابتدا وارد حساب خود شوید.")
                    "invalid" -> toast("اطلاعات نظرسنجی معتبر نیست؛ سؤال و گزینه‌ها را بررسی کنید.")
                    else -> toast("ساخت نظرسنجی انجام نشد؛ اینترنت را بررسی کنید.")
                }
            }
        }
    }
}
