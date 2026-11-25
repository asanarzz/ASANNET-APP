package com.kafinet.asannet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.io.FileOutputStream

/** نتیجه‌ی نظرسنجی را به‌صورت یک عکس PNG می‌کشد تا در هر شبکه‌ای قابل ارسال باشد. */
object PollResultImage {

    private const val W = 1080
    private const val PAD = 56

    fun createFile(context: Context, p: PollDetail): File {
        val bmp = render(context, p)
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        val file = File(dir, "poll_result_" + p.code + ".png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun render(context: Context, p: PollDetail): Bitmap {
        val font = ResourcesCompat.getFont(context, R.font.yekan_boom)
        val cText = Color.parseColor("#1E1B2E")
        val cMuted = Color.parseColor("#6B7280")
        val cPrimary = Color.parseColor("#4F46E5")
        val contentW = W - 2 * PAD

        fun tp(size: Float, color: Int, bold: Boolean = false): TextPaint {
            val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
            t.textSize = size
            t.color = color
            if (font != null) t.typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
            return t
        }

        fun layout(text: String, paint: TextPaint, width: Int, opposite: Boolean = false, center: Boolean = false): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(
                    when {
                        center -> Layout.Alignment.ALIGN_CENTER
                        opposite -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                )
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setLineSpacing(0f, 1.2f)
                .build()

        val headerText = if (p.organizer.isNotBlank()) p.organizer else "نظرسنجی آسان نت"
        val headerL = layout(headerText, tp(64f, Color.WHITE, true), contentW)
        val headerH = headerL.height + 110

        val titleL = layout(p.title, tp(70f, cText, true), contentW)

        val meta = ArrayList<String>()
        p.totalVoters?.let { meta.add(PollFormat.digits(it) + " نفر شرکت کرده‌اند") }
        meta.add(PollFormat.timeLeft(p.endsAt, p.closed))
        val metaL = layout(meta.joinToString("   •   "), tp(46f, cMuted), contentW)

        val total = p.totalVoters ?: 0
        val n = maxOf(p.options.size, 1)
        val colW = contentW / n.toFloat()
        val maxVotes = p.options.maxOfOrNull { it.votes ?: 0 } ?: 0
        val labelSize = when {
            n <= 3 -> 52f
            n <= 5 -> 44f
            n <= 7 -> 35f
            else -> 29f
        }
        val valueSize = if (n <= 5) 56f else 44f
        val pctSize = if (n <= 5) 42f else 34f
        val chartH = 520

        class Col(
            val votes: Int,
            val pct: Int,
            val mine: Boolean,
            val count: StaticLayout,
            val percent: StaticLayout,
            val label: StaticLayout
        )

        val cols = p.options.map { o ->
            val v = o.votes ?: 0
            val pct = if (total > 0) v * 100 / total else 0
            val mine = p.myVotes.contains(o.id)
            val w = (colW - 10f).toInt()
            Col(
                v, pct, mine,
                layout(PollFormat.digits(v), tp(valueSize, cText, true), w, center = true),
                layout(PollFormat.digits(pct) + "٪", tp(pctSize, cMuted), w, center = true),
                layout(o.label, tp(labelSize, cText, mine), w, center = true)
            )
        }
        val valueBlockH = (cols.firstOrNull()?.let { it.count.height + it.percent.height } ?: 0) + 18
        val labelMaxH = cols.maxOfOrNull { it.label.height } ?: 0

        val footerL = layout("ساخته‌شده با برنامه آسان نت", tp(42f, cMuted), contentW, center = true)

        var height = headerH + 50 + titleL.height + 16 + metaL.height + 50
        height += valueBlockH + chartH + 24 + labelMaxH + 50 + footerL.height + 50

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        // هدر
        val hp = Paint(Paint.ANTI_ALIAS_FLAG)
        hp.shader = LinearGradient(0f, 0f, W.toFloat(), 0f, Color.parseColor("#4F46E5"), Color.parseColor("#818CF8"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), headerH.toFloat(), hp)
        c.save()
        c.translate(PAD.toFloat(), 55f)
        headerL.draw(c)
        c.restore()

        var y = headerH + 50
        c.save(); c.translate(PAD.toFloat(), y.toFloat()); titleL.draw(c); c.restore()
        y += titleL.height + 16
        c.save(); c.translate(PAD.toFloat(), y.toFloat()); metaL.draw(c); c.restore()
        y += metaL.height + 50

        // نمودار ستونی (اولین گزینه سمت راست)
        val baseY = (y + valueBlockH + chartH).toFloat()
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.color = Color.parseColor("#E5E7EB")
        line.strokeWidth = 4f
        c.drawLine(PAD.toFloat(), baseY, (W - PAD).toFloat(), baseY, line)

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val barW = minOf(colW * 0.6f, 150f)
        for ((i, col) in cols.withIndex()) {
            val cx = (W - PAD) - colW * (i + 0.5f)
            var h = if (maxVotes > 0) chartH * col.votes.toFloat() / maxVotes else 0f
            if (col.votes > 0 && h < 14f) h = 14f
            if (col.votes == 0) h = 6f
            val top = baseY - h
            bar.color = when {
                col.votes == 0 -> Color.parseColor("#E5E7EB")
                col.votes == maxVotes -> Color.parseColor("#4F46E5")
                else -> Color.parseColor("#A5B4FC")
            }
            c.drawRoundRect(RectF(cx - barW / 2, top, cx + barW / 2, baseY), 18f, 18f, bar)

            // تعداد و درصد بالای ستون
            val vy = top - col.count.height - col.percent.height - 10f
            c.save(); c.translate(cx - col.count.width / 2f, vy); col.count.draw(c); c.restore()
            c.save(); c.translate(cx - col.percent.width / 2f, vy + col.count.height); col.percent.draw(c); c.restore()

            // نام گزینه زیر ستون
            c.save(); c.translate(cx - col.label.width / 2f, baseY + 24f); col.label.draw(c); c.restore()
        }

        val fy = baseY + 24f + labelMaxH + 50f
        c.save(); c.translate(PAD.toFloat(), fy); footerL.draw(c); c.restore()
        return bmp
    }
}
