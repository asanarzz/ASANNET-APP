package com.kafinet.asannet

import android.content.Intent
import android.view.View
import android.widget.LinearLayout

/**
 * صفحه‌ی اصلی بخش «عکس». دو پوشه‌ی کاملاً جدا از هم:
 *  ۱) ابزارهای عکس (تغییر اندازه، عکس پرسنلی، تبدیل فرمت، PDF و ...) ← کارِ خودِ برنامه روی گوشی
 *  ۲) لینک‌ها و فایل‌ها ← همان فهرست قبلیِ بخش عکس که از پنل مدیریت پر می‌شود
 *     (هر لینک، فایل، گالری یا پوشه‌ای که در پنل برای بخش «عکس» بگذارید همین‌جا دیده می‌شود)
 */
class PhotoHubActivity : PhotoToolActivity() {

    companion object {
        const val EXTRA_LABEL = "extra_label"
    }

    override fun toolTitle(): String {
        val fromIntent = intent.getStringExtra(EXTRA_LABEL)
        return if (fromIntent.isNullOrBlank()) getString(R.string.cat_image) else fromIntent
    }

    private fun addRow(container: LinearLayout, row: View) {
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.bottomMargin = dp(12)
        container.addView(row, lp)
    }

    override fun buildContent(container: LinearLayout) {
        addRow(
            container,
            PhotoUi.menuRow(
                this,
                R.drawable.ic_image,
                PhotoUi.color(this, R.color.type_image),
                "ابزارهای عکس",
                "تغییر اندازه و حجم، عکس پرسنلی ۳×۴ و ۴×۶، تبدیل فرمت، عکس به PDF و ..."
            ) {
                startActivity(Intent(this, PhotoToolsActivity::class.java))
            }
        )

        addRow(
            container,
            PhotoUi.menuRow(
                this,
                R.drawable.ic_link,
                PhotoUi.color(this, R.color.type_link),
                "لینک‌ها و فایل‌ها",
                "سایت‌ها، فایل‌ها و گالری‌هایی که آسان نت برای شما گذاشته است"
            ) {
                val intent = Intent(this, CategoryListActivity::class.java)
                intent.putExtra(CategoryListActivity.EXTRA_TYPE, ContentType.IMAGE.key)
                intent.putExtra(CategoryListActivity.EXTRA_LABEL, toolTitle())
                startActivity(intent)
            }
        )
    }
}

/** فهرست ابزارهای عکس. */
class PhotoToolsActivity : PhotoToolActivity() {

    override fun toolTitle(): String = "ابزارهای عکس"

    private fun addTool(container: LinearLayout, iconRes: Int, colorRes: Int, title: String, subtitle: String, target: Class<*>) {
        val row = PhotoUi.menuRow(this, iconRes, PhotoUi.color(this, colorRes), title, subtitle) {
            startActivity(Intent(this, target))
        }
        val lp = LinearLayout.LayoutParams(PhotoUi.MATCH, PhotoUi.WRAP)
        lp.bottomMargin = dp(12)
        container.addView(row, lp)
    }

    override fun buildContent(container: LinearLayout) {
        addTool(
            container, R.drawable.ic_aspect_ratio, R.color.type_image,
            "تغییر اندازه و کم‌کردن حجم",
            "حجم دلخواه (مثلاً زیر ۱۰۰ کیلوبایت) یا ابعاد مشخص؛ مناسب سامانه‌ها",
            PhotoResizeActivity::class.java
        )
        addTool(
            container, R.drawable.ic_person_placeholder, R.color.primary,
            "عکس پرسنلی",
            "برش استاندارد ۳×۴ و ۴×۶ و ...، همراه با برگه‌ی چاپ",
            PhotoIdActivity::class.java
        )
        addTool(
            container, R.drawable.ic_refresh, R.color.type_link,
            "تبدیل فرمت عکس",
            "تبدیل بین JPG، PNG، WebP و BMP",
            PhotoConvertActivity::class.java
        )
        addTool(
            container, R.drawable.ic_file, R.color.type_file,
            "عکس به PDF",
            "چند عکس را در یک فایل PDF جمع کنید",
            PhotoPdfActivity::class.java
        )
        addTool(
            container, R.drawable.ic_screen_rotate, R.color.type_software,
            "ویرایش: برش، چرخش و نور",
            "برش، چرخش، آینه، نور و کنتراست و حالت اسکن سند",
            PhotoEditorActivity::class.java
        )
        addTool(
            container, R.drawable.ic_vpn_shield, R.color.danger,
            "واترمارک مدارک",
            "نوشتن «فقط برای ...» روی عکس مدارک تا کسی سوءاستفاده نکند",
            PhotoWatermarkActivity::class.java
        )
    }
}
