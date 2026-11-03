package com.kafinet.asannet

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * دکمه‌ی ✕ بالای صفحه‌های نمایش (وب، عکس، گالری، جزئیات فایل، جزئیات فیلم، بنر، آگهی).
 * با لمس ✕، صفحه بسته می‌شه و کاربر به صفحه‌ی لیستِ همون پوشه برمی‌گرده (نه فقط یه قدم عقب).
 * اگه صفحه از خونه‌ی اصلی باز شده باشه (لیستی وسط نیست)، برمی‌گرده به همون خونه.
 *
 * ✕ خودکار به نوار بالای این صفحه‌ها اضافه می‌شه؛ برای همین لازم نیست تو خودِ صفحه‌ها چیزی عوض بشه.
 * رادیو، تلویزیون (ExoPlayer / VideoPlayer) و پلیر M3U عمداً تو فهرست نیستن و دست‌نخورده‌ان.
 */
object CloseButtonInjector {

    private val targets = setOf<Class<out Activity>>(
        WebViewActivity::class.java,
        ImageViewerActivity::class.java,
        GalleryDetailActivity::class.java,
        MediaDetailActivity::class.java,
        VideoDetailActivity::class.java,
        BannerDetailActivity::class.java,
        AdDetailActivity::class.java
    )

    /** تعداد صفحه‌های لیست (CategoryListActivity) که الان تو پشته‌ی برنامه باز هستن. */
    @Volatile
    private var listsAlive = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is CategoryListActivity) listsAlive++
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity.javaClass in targets) addCloseButton(activity)
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is CategoryListActivity && listsAlive > 0) listsAlive--
            }
        })
    }

    private fun addCloseButton(activity: Activity) {
        val back = activity.findViewById<View>(R.id.btn_back) ?: return
        // اگه قبلاً اضافه شده، دوباره اضافه نمی‌کنیم
        if (back.getTag(R.id.btn_back) != null) return
        val bar = back.parent as? LinearLayout ?: return
        if (bar.orientation != LinearLayout.HORIZONTAL) return
        back.setTag(R.id.btn_back, true)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        // اگه تو نوار هیچ ویوی «کشیده‌شونده» نیست، یه فاصله‌ی کشسان می‌ذاریم تا ✕ بره انتهای نوار
        var hasStretch = false
        for (i in 0 until bar.childCount) {
            val lp = bar.getChildAt(i).layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.weight > 0f) hasStretch = true
        }
        if (!hasStretch) {
            bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        }

        val close = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
            setOnClickListener { closeToFolder(activity) }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun closeToFolder(activity: Activity) {
        if (listsAlive > 0) {
            // همه‌ی صفحه‌های بالای لیست بسته می‌شن و همون لیست (با همون جای اسکرول) باز می‌مونه
            val intent = Intent(activity, CategoryListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(intent)
        } else {
            activity.finish()
        }
    }
}
