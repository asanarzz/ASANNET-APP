package com.kafinet.asannet

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * پنجره‌ی یکدست برنامه: کارت گرد، عنوان، متن و دکمه‌های تمام‌عرض (مثل پنجره‌ی آهنگ‌ها و فیلم‌ها).
 * API شبیه AlertDialog.Builder است تا همه‌ی پنجره‌های برنامه با همین ظاهر ساخته شوند.
 */
class StyledDialog internal constructor(context: Context) : Dialog(context) {

    internal val buttons = HashMap<Int, Button>()

    /** مثل AlertDialog: بعد از show می‌شود روی دکمه‌ها listener گذاشت تا پنجره خودکار بسته نشود. */
    fun getButton(which: Int): Button = buttons[which] ?: Button(context)

    class Builder(val context: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private var items: Array<out CharSequence>? = null
        private var itemsListener: DialogInterface.OnClickListener? = null
        private var positiveText: CharSequence? = null
        private var positiveListener: DialogInterface.OnClickListener? = null
        private var negativeText: CharSequence? = null
        private var negativeListener: DialogInterface.OnClickListener? = null
        private var neutralText: CharSequence? = null
        private var neutralListener: DialogInterface.OnClickListener? = null
        private var cancelable = true
        private var onCancel: DialogInterface.OnCancelListener? = null
        private var onDismiss: DialogInterface.OnDismissListener? = null

        fun setTitle(t: CharSequence?): Builder { title = t; return this }
        fun setTitle(@StringRes id: Int): Builder { title = context.getString(id); return this }
        fun setMessage(m: CharSequence?): Builder { message = m; return this }
        fun setMessage(@StringRes id: Int): Builder { message = context.getString(id); return this }
        fun setView(v: View?): Builder { view = v; return this }
        fun setCancelable(c: Boolean): Builder { cancelable = c; return this }
        fun setOnCancelListener(l: DialogInterface.OnCancelListener?): Builder { onCancel = l; return this }
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?): Builder { onDismiss = l; return this }

        fun setItems(list: Array<out CharSequence>, l: DialogInterface.OnClickListener?): Builder {
            items = list
            itemsListener = l
            return this
        }

        fun setPositiveButton(t: CharSequence?, l: DialogInterface.OnClickListener?): Builder {
            positiveText = t; positiveListener = l; return this
        }
        fun setPositiveButton(@StringRes id: Int, l: DialogInterface.OnClickListener?): Builder =
            setPositiveButton(context.getString(id), l)

        fun setNegativeButton(t: CharSequence?, l: DialogInterface.OnClickListener?): Builder {
            negativeText = t; negativeListener = l; return this
        }
        fun setNegativeButton(@StringRes id: Int, l: DialogInterface.OnClickListener?): Builder =
            setNegativeButton(context.getString(id), l)

        fun setNeutralButton(t: CharSequence?, l: DialogInterface.OnClickListener?): Builder {
            neutralText = t; neutralListener = l; return this
        }
        fun setNeutralButton(@StringRes id: Int, l: DialogInterface.OnClickListener?): Builder =
            setNeutralButton(context.getString(id), l)

        fun show(): StyledDialog {
            val d = create()
            d.show()
            return d
        }

        fun create(): StyledDialog {
            val dialog = StyledDialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val dm = context.resources.displayMetrics
            fun dp(v: Int): Int = (v * dm.density + 0.5f).toInt()
            val font: Typeface? = ResourcesCompat.getFont(context, R.font.yekan_boom)
            fun bold(): Typeface? = font?.let { Typeface.create(it, Typeface.BOLD) }
            val cPrimary = ContextCompat.getColor(context, R.color.primary)
            val cText = ContextCompat.getColor(context, R.color.text_main)
            val cMuted = ContextCompat.getColor(context, R.color.text_muted)
            val cSurface = ContextCompat.getColor(context, R.color.surface)
            val cWhite = ContextCompat.getColor(context, R.color.white)

            val card = LinearLayout(context)
            card.orientation = LinearLayout.VERTICAL
            card.layoutDirection = View.LAYOUT_DIRECTION_RTL
            card.setPadding(dp(22), dp(20), dp(22), dp(16))
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(22).toFloat()
            bg.setColor(cSurface)
            card.background = bg

            fun lp(top: Int = 0): LinearLayout.LayoutParams {
                val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.topMargin = dp(top)
                return p
            }

            if (!title.isNullOrBlank()) {
                val t = TextView(context)
                t.text = title
                t.textSize = 19f
                t.setTextColor(cText)
                bold()?.let { t.typeface = it }
                t.gravity = Gravity.START
                t.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
                card.addView(t, lp())
            }

            if (!message.isNullOrBlank()) {
                val m = TextView(context)
                m.text = message
                m.textSize = 15f
                m.setTextColor(cText)
                font?.let { m.typeface = it }
                m.setLineSpacing(0f, 1.25f)
                m.gravity = Gravity.START
                m.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
                val sv = CappedScrollView(context, (dm.heightPixels * 0.4f).toInt())
                sv.addView(m)
                card.addView(sv, lp(if (title.isNullOrBlank()) 0 else 12))
            }

            view?.let { v ->
                (v.parent as? ViewGroup)?.removeView(v)
                card.addView(v, lp(12))
            }

            fun styled(text: CharSequence, filled: Boolean, which: Int, tint: Int): Button {
                val b = Button(context)
                b.text = text
                b.isAllCaps = false
                b.textSize = 15f
                bold()?.let { b.typeface = it }
                b.stateListAnimator = null
                b.minHeight = 0
                b.minimumHeight = 0
                b.setPadding(dp(12), 0, dp(12), 0)
                val g = GradientDrawable()
                g.shape = GradientDrawable.RECTANGLE
                g.cornerRadius = dp(12).toFloat()
                if (filled) {
                    g.setColor(tint)
                    b.setTextColor(cWhite)
                } else {
                    g.setColor(Color.TRANSPARENT)
                    g.setStroke(dp(1), tint)
                    b.setTextColor(tint)
                }
                b.background = g
                dialog.buttons[which] = b
                return b
            }

            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL

            val itemList = items
            if (itemList != null && itemList.isNotEmpty()) {
                val filled = itemList.size <= 4
                itemList.forEachIndexed { i, label ->
                    val b = styled(label, filled, 1000 + i, cPrimary)
                    b.setOnClickListener {
                        dialog.dismiss()
                        itemsListener?.onClick(dialog, i)
                    }
                    col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (filled) 50 else 46)).also {
                        if (i > 0) it.topMargin = dp(10)
                    })
                }
            }
            val hasItems = col.childCount > 0

            fun addAction(text: CharSequence?, listener: DialogInterface.OnClickListener?, which: Int, filled: Boolean, tint: Int) {
                if (text.isNullOrBlank()) return
                val b = styled(text, filled, which, tint)
                b.setOnClickListener {
                    dialog.dismiss()
                    listener?.onClick(dialog, which)
                }
                col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).also {
                    if (col.childCount > 0) it.topMargin = dp(10)
                })
            }
            addAction(positiveText, positiveListener, DialogInterface.BUTTON_POSITIVE, true, cPrimary)
            addAction(neutralText, neutralListener, DialogInterface.BUTTON_NEUTRAL, false, cPrimary)
            addAction(negativeText, negativeListener, DialogInterface.BUTTON_NEGATIVE, false, cMuted)

            if (col.childCount > 0) {
                val hasAbove = !title.isNullOrBlank() || !message.isNullOrBlank() || view != null
                if (hasItems && itemList != null && itemList.size > 4) {
                    val sv = CappedScrollView(context, (dm.heightPixels * 0.5f).toInt())
                    sv.addView(col)
                    card.addView(sv, lp(if (hasAbove) 14 else 0))
                } else {
                    card.addView(col, lp(if (hasAbove) 14 else 0))
                }
            }

            dialog.setContentView(card)
            dialog.setCancelable(cancelable)
            dialog.setCanceledOnTouchOutside(cancelable)
            onCancel?.let { dialog.setOnCancelListener(it) }
            onDismiss?.let { dialog.setOnDismissListener(it) }

            dialog.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = minOf((dm.widthPixels * 0.9f).toInt(), dp(440))
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                w.setGravity(Gravity.CENTER)
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            return dialog
        }
    }

    /** ScrollView که بلندیش از حد مشخصی بیشتر نمی‌شود (برای متن/لیست‌های بلند). */
    private class CappedScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }
}
