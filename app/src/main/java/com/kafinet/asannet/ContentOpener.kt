package com.kafinet.asannet

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity

/**
 * یک نقطه‌ی واحد برای باز کردن هر لینک یا فایلی، در هر گوشه‌ی برنامه که باشه —
 * صفحه‌ی اصلی، داخل یه دسته، داخل یه پوشه‌ی بخش‌بندی‌شده، یا صفحه‌ی جزئیات یه آیتم.
 * تصمیم می‌گیره بر اساس «پسوند فایل»، نه نوع دسته‌بندی — یعنی مثلاً اگه یه لینک
 * تصویری تو دسته‌ی «لینک» گذاشته بشه، بازم به‌جای مرورگر، تو نمایشگر عکس باز می‌شه.
 */
object ContentOpener {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val audioExtensions = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "3gp", "m3u8")

    fun open(activity: AppCompatActivity, url: String, title: String) {
        if (url.isBlank()) return

        val cleanUrl = url.substringBefore("?").substringBefore("#").lowercase()
        val extension = cleanUrl.substringAfterLast('.', "")

        when (extension) {
            "html", "htm" -> openWeb(activity, resolveUrl(url), title, allowDownload = true)
            "pdf" -> openPdf(activity, url, title)
            "apk" -> DownloadHelper.downloadAndInstallApk(activity, url, title)
            in imageExtensions -> openImage(activity, url, title)
            in audioExtensions -> openAudio(activity, url, title)
            "m3u" -> {
                activity.startActivity(
                    Intent(activity, M3uPlaylistActivity::class.java).apply {
                        putExtra(M3uPlaylistActivity.EXTRA_URL, url)
                        putExtra(M3uPlaylistActivity.EXTRA_TITLE, title)
                    }
                )
            }
            in videoExtensions -> openVideo(activity, url, title)
            else -> {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    openWeb(activity, url, title, allowDownload = false)
                } else if (DownloadHelper.ensureStoragePermission(activity)) {
                    DownloadHelper.downloadUrl(activity, url, title)
                }
            }
        }
    }

    /**
     * برای مواقعی که از قبل مشخصه محتوا یه فایل قابل‌دانلود ساده‌ست (نه لینک وب) —
     * اگه پسوندش شناخته‌شده باشه (عکس/صدا/ویدیو/پی‌دی‌اف/اچ‌تی‌ام‌ال/apk) داخل اپ باز
     * می‌شه، وگرنه دانلود می‌شه.
     */
    fun openFile(activity: AppCompatActivity, url: String, title: String) {
        open(activity, url, title)
    }

    private fun openWeb(activity: AppCompatActivity, url: String, title: String, allowDownload: Boolean) {
        val intent = Intent(activity, WebViewActivity::class.java)
        intent.putExtra(WebViewActivity.EXTRA_URL, url)
        intent.putExtra(WebViewActivity.EXTRA_TITLE, title)
        intent.putExtra(WebViewActivity.EXTRA_ALLOW_DOWNLOAD, allowDownload)
        activity.startActivity(intent)
    }

    private fun openImage(activity: AppCompatActivity, url: String, title: String) {
        val intent = Intent(activity, ImageViewerActivity::class.java)
        intent.putExtra(ImageViewerActivity.EXTRA_URL, url)
        intent.putExtra(ImageViewerActivity.EXTRA_TITLE, title)
        activity.startActivity(intent)
    }

    private fun openAudio(activity: AppCompatActivity, url: String, title: String) {
        // همون سرویس پخش پس‌زمینه‌ای که برای رادیو استفاده می‌شه، برای هر فایل صوتی
        // دیگه‌ای هم کار می‌کنه — با خروج از اپ یا خاموش‌شدن صفحه قطع نمی‌شه
        val intent = Intent(activity, RadioPlayerActivity::class.java)
        intent.putExtra(RadioPlayerActivity.EXTRA_URL, url)
        intent.putExtra(RadioPlayerActivity.EXTRA_TITLE, title)
        activity.startActivity(intent)
    }

    private fun openVideo(activity: AppCompatActivity, url: String, title: String) {
        val intent = Intent(activity, VideoPlayerActivity::class.java)
        intent.putExtra(VideoPlayerActivity.EXTRA_URL, url)
        intent.putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
        activity.startActivity(intent)
    }

    private fun openPdf(activity: AppCompatActivity, url: String, title: String) {
        // خود وب‌ویوی اندروید پی‌دی‌اف رو مستقیم نمایش نمی‌ده؛ از نمایشگر آنلاین گوگل
        // به‌عنوان یه نمایشگر پی‌دی‌اف داخل همون صفحه‌ی وب داخلی اپ استفاده می‌کنیم
        val viewerUrl = "https://docs.google.com/gview?embedded=true&url=" + Uri.encode(url)
        openWeb(activity, viewerUrl, title, allowDownload = true)
    }

    private fun resolveUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "file:///android_asset/${url.removePrefix("/")}"
        }
    }
}
