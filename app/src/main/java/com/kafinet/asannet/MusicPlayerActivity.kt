package com.kafinet.asannet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C

/**
 * صفحه‌ی پلیر موزیک (جدا از صفحه‌ی رادیو). نمایش آهنگ فعلی، نوار پیشرفت دانلود موقع
 * «در حال آماده‌سازی فایل…»، نوار پخش، قبلی/بعدی، و انتخاب بین «پخش همه» و «یکی‌یکی».
 * تمام کنترل‌ها از طریق MusicPlayerService انجام می‌شه؛ بستن این صفحه پخش رو قطع نمی‌کنه.
 */
class MusicPlayerActivity : AppCompatActivity() {

    companion object {
        /** سرویس رو با صف آهنگ‌ها استارت می‌کنه و صفحه‌ی پلیر رو باز می‌کنه. */
        fun launch(context: Context, titles: List<String>, urls: List<String>, startIndex: Int, playAll: Boolean) {
            MusicPlayerService.start(context, titles, urls, startIndex, playAll)
            context.startActivity(Intent(context, MusicPlayerActivity::class.java))
        }
    }

    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var errorView: TextView
    private lateinit var prepareBox: LinearLayout
    private lateinit var prepareBar: ProgressBar
    private lateinit var prepareDetail: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeView: TextView
    private lateinit var playButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var modeButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var listener: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateSeek()
            handler.postDelayed(this, 500)
        }
    }

    private fun toFa(s: String): String =
        s.map { c ->
            when {
                c in '0'..'9' -> '۰' + (c - '0')
                c == '.' -> '٫'
                else -> c
            }
        }.joinToString("")

    private fun mb(bytes: Long): String =
        toFa(String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0))

    private fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return toFa(String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "پخش موزیک"

        titleView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        counterView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        errorView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFD32F2F.toInt())
            visibility = View.GONE
        }

        val prepareStatus = TextView(this).apply {
            text = "در حال آماده‌سازی فایل…"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        prepareBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        prepareDetail = TextView(this).apply {
            text = "در حال اتصال…"
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.8f
        }
        prepareBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(prepareStatus)
            addView(
                prepareBar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                }
            )
            addView(prepareDetail)
        }

        seekBar = SeekBar(this)
        timeView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.8f
        }

        prevButton = Button(this).apply {
            text = "قبلی"
            setOnClickListener { sendAction(MusicPlayerService.ACTION_PREV) }
        }
        playButton = Button(this).apply {
            text = "پخش"
            setOnClickListener { sendAction(MusicPlayerService.ACTION_TOGGLE) }
        }
        nextButton = Button(this).apply {
            text = "بعدی"
            setOnClickListener { sendAction(MusicPlayerService.ACTION_NEXT) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 3f
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
            addView(prevButton, lp())
            addView(playButton, lp())
            addView(nextButton, lp())
        }

        modeButton = Button(this).apply {
            setOnClickListener {
                val intent = Intent(this@MusicPlayerActivity, MusicPlayerService::class.java).apply {
                    action = MusicPlayerService.ACTION_SET_MODE
                    putExtra(MusicPlayerService.EXTRA_PLAY_ALL, !MusicPlayerService.playAll)
                }
                startService(intent)
            }
        }
        val stopButton = Button(this).apply {
            text = "توقف و خروج"
            setOnClickListener { sendAction(MusicPlayerService.ACTION_STOP) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            val full = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(titleView, full())
            addView(counterView, full().apply { topMargin = dp(6) })
            addView(errorView, full().apply { topMargin = dp(10) })
            addView(prepareBox, full().apply { topMargin = dp(28) })
            addView(seekBar, full().apply { topMargin = dp(28) })
            addView(timeView, full())
            addView(controls, full().apply { topMargin = dp(20) })
            addView(modeButton, full().apply { topMargin = dp(28) })
            addView(stopButton, full().apply { topMargin = dp(8) })
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeView.text = clock(progress.toLong()) + " / " + clock(MusicPlayerService.currentPlayer?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                userSeeking = false
                MusicPlayerService.currentPlayer?.seekTo((bar?.progress ?: 0).toLong())
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val l: () -> Unit = { render() }
        listener = l
        MusicPlayerService.stateListener = l
        render()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        if (MusicPlayerService.stateListener === listener) MusicPlayerService.stateListener = null
        listener = null
        super.onStop()
    }

    private fun sendAction(action: String) {
        startService(Intent(this, MusicPlayerService::class.java).setAction(action))
    }

    private fun render() {
        if (!MusicPlayerService.isActive) {
            // سرویس هنوز بالا نیومده (چند لحظه‌ی اول) یا پخش متوقف شده
            if (!MusicPlayerService.starting) finish()
            return
        }
        val titles = MusicPlayerService.queueTitles
        val index = MusicPlayerService.currentIndex
        titleView.text = titles.getOrNull(index).orEmpty()
        counterView.text = if (titles.size > 1) {
            "آهنگ " + toFa((index + 1).toString()) + " از " + toFa(titles.size.toString())
        } else ""

        val error = MusicPlayerService.errorText
        errorView.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        errorView.text = error.orEmpty()

        val preparing = MusicPlayerService.isPreparing
        prepareBox.visibility = if (preparing) View.VISIBLE else View.GONE
        seekBar.visibility = if (preparing) View.INVISIBLE else View.VISIBLE
        timeView.visibility = if (preparing) View.INVISIBLE else View.VISIBLE
        if (preparing) {
            val done = MusicPlayerService.downloadedBytes
            val total = MusicPlayerService.totalBytes
            if (total > 0) {
                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                prepareBar.isIndeterminate = false
                prepareBar.progress = percent
                prepareDetail.text = toFa(percent.toString()) + "٪ — " + mb(done) + " از " + mb(total) + " مگابایت"
            } else if (done > 0) {
                prepareBar.isIndeterminate = true
                prepareDetail.text = mb(done) + " مگابایت دانلود شد"
            } else {
                prepareBar.isIndeterminate = true
                prepareDetail.text = "در حال اتصال…"
            }
        }

        playButton.text = if (MusicPlayerService.isPlayingNow) "مکث" else "پخش"
        playButton.isEnabled = !preparing
        prevButton.isEnabled = true
        nextButton.isEnabled = index + 1 < titles.size
        modeButton.text = if (MusicPlayerService.playAll) {
            "حالت پخش: پخش همه (پشت‌سرهم) — برای تغییر بزن"
        } else {
            "حالت پخش: یکی‌یکی — برای تغییر بزن"
        }
        updateSeek()
    }

    private fun updateSeek() {
        if (userSeeking || MusicPlayerService.isPreparing) return
        val player = MusicPlayerService.currentPlayer ?: return
        try {
            val duration = player.duration
            val position = player.currentPosition
            if (duration != C.TIME_UNSET && duration > 0) {
                seekBar.max = duration.toInt()
                seekBar.progress = position.toInt().coerceIn(0, duration.toInt())
                timeView.text = clock(position) + " / " + clock(duration)
            } else {
                timeView.text = clock(position)
            }
        } catch (e: Exception) {
            // بی‌اهمیت
        }
    }
}
