package com.kafinet.asannet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object DownloadHelper {

    const val STORAGE_PERMISSION_REQUEST_CODE = 1001

    /**
     * روی اندروید ۹ و پایین‌تر برای ذخیره در پوشه‌ی عمومی Downloads نیاز به اجازه است.
     * از اندروید ۱۰ به بعد نیازی نیست. اگر true برگرداند یعنی می‌توان دانلود را شروع کرد،
     * در غیر این صورت اجازه درخواست می‌شود و کاربر باید دوباره روی دکمه‌ی دانلود بزند.
     */
    fun ensureStoragePermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return true
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE)
        }
        return granted
    }

    /** فایل را از لینک داده‌شده در پوشه‌ی عمومی Downloads گوشی ذخیره می‌کند. */
    fun downloadUrl(context: Context, url: String, title: String) {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fileName = title.ifBlank { "kafinet_file" }.replace(Regex("[^A-Za-z0-9آ-ی_\\- ]"), "_") +
                guessExtension(url)

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            AppDownloadManager.registerDownload(context, id, fileName, url)
            Toast.makeText(context, "در حال دانلود در پوشه‌ی Downloads…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
    }

    /** یک data URI (مثل خروجی canvas.toDataURL که ابزارهای HTML تولید می‌کنند) را
     *  در پوشه‌ی عمومی Downloads ذخیره می‌کند. */
    fun saveDataUri(context: Context, dataUri: String, suggestedName: String): Boolean {
        return try {
            val commaIndex = dataUri.indexOf(',')
            if (!dataUri.startsWith("data:") || commaIndex == -1) return false

            val header = dataUri.substring(5, commaIndex)
            val mimeType = header.substringBefore(';').ifBlank { "application/octet-stream" }
            val isBase64 = header.contains("base64")
            val payload = dataUri.substring(commaIndex + 1)
            val bytes = if (isBase64) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }

            val ext = when {
                mimeType.contains("png") -> ".png"
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                mimeType.contains("pdf") -> ".pdf"
                mimeType.contains("webp") -> ".webp"
                else -> ""
            }
            val baseName = suggestedName.substringBeforeLast('.').ifBlank { "kafinet_file" }
                .replace(Regex("[^A-Za-z0-9آ-ی_\\- ]"), "_")
            val fileName = baseName + ext

            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, fileName).outputStream().use { it.write(bytes) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun guessExtension(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#")
        val lastDot = clean.lastIndexOf('.')
        val lastSlash = clean.lastIndexOf('/')
        return if (lastDot > lastSlash && lastDot != -1) clean.substring(lastDot) else ""
    }

    /**
     * فایل APK را با نوار پیشرفت دانلود می‌کند و بعد صفحه‌ی نصب اندروید را باز می‌کند.
     * اگر اجازه‌ی «نصب از این منبع» روشن نباشد، اول کاربر را به همان تنظیمات می‌برد.
     */
    fun downloadAndInstallApk(context: Context, url: String, title: String) {
        if (context !is Activity) {
            downloadUrl(context, url, title)
            return
        }
        val activity: Activity = context

        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("اجازه‌ی نصب برنامه")
                .setMessage(
                    "برای نصب نسخه‌ی جدید، در صفحه‌ی بعد گزینه‌ی «اجازه از این منبع» را روشن کنید. " +
                        "بعد برگردید و دوباره روی «به‌روزرسانی برنامه» بزنید."
                )
                .setPositiveButton("باز کردن تنظیمات") { _, _ ->
                    try {
                        activity.startActivity(
                            android.content.Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.packageName)
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(activity, "تنظیمات باز نشد", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("بعداً", null)
                .show()
            return
        }

        showApkDownloadDialog(activity, url, title)
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

    /** پنجره‌ی پیشرفت دانلود: درصد، حجم دانلودشده و دکمه‌ی لغو. بعد از دانلود، نصب خودکار باز می‌شود. */
    private fun showApkDownloadDialog(activity: Activity, url: String, title: String) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val builder = AlertDialog.Builder(activity)
        val ctx = builder.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val versionLabel = title.removePrefix("ASANNET-").takeIf { it != title && it.isNotBlank() }
        val statusText = TextView(ctx).apply {
            text = if (versionLabel != null) "در حال دانلود نسخه‌ی " + toFa(versionLabel) else "در حال دانلود نسخه‌ی جدید"
            textSize = 17f
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val detailText = TextView(ctx).apply {
            text = "در حال اتصال…"
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val hintText = TextView(ctx).apply {
            text = "لطفاً تا پایان دانلود برنامه را نبندید.\nبعد از دانلود، صفحه‌ی نصب خودکار باز می‌شود."
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(24), dp(24), dp(8))
            addView(statusText)
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(18)
                    bottomMargin = dp(10)
                }
            )
            addView(detailText)
            addView(
                hintText,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
            )
        }

        val dialog = builder
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("لغو") { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()

        fun dismissSafely() {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (e: Exception) {
            }
        }

        Thread {
            var target: java.io.File? = null
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP " + connection.responseCode)
                }
                val total = connection.contentLength.toLong()

                val shareDir = java.io.File(activity.cacheDir, "share")
                if (!shareDir.exists()) shareDir.mkdirs()
                val fileName = title.ifBlank { "app" }.replace(Regex("[^A-Za-z0-9آ-ی_\\- ]"), "_") + ".apk"
                val file = java.io.File(shareDir, fileName)
                target = file

                if (total > 0) {
                    activity.runOnUiThread { bar.isIndeterminate = false }
                }

                var downloaded = 0L
                var lastUpdate = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (!cancelled.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                val d = downloaded
                                activity.runOnUiThread {
                                    if (total > 0) {
                                        val percent = (d * 100 / total).toInt().coerceIn(0, 100)
                                        bar.progress = percent
                                        detailText.text = toFa(percent.toString()) + "٪ — " + mb(d) + " از " + mb(total) + " مگابایت"
                                    } else {
                                        detailText.text = mb(d) + " مگابایت دانلود شد"
                                    }
                                }
                            }
                        }
                    }
                }

                if (cancelled.get()) {
                    file.delete()
                    activity.runOnUiThread { dismissSafely() }
                    return@Thread
                }
                if (total > 0 && downloaded < total) {
                    throw java.io.IOException("incomplete download")
                }

                activity.runOnUiThread {
                    dismissSafely()
                    launchApkInstall(activity, file)
                }
            } catch (e: Exception) {
                try {
                    target?.delete()
                } catch (ignored: Exception) {
                }
                activity.runOnUiThread {
                    dismissSafely()
                    if (!cancelled.get()) {
                        Toast.makeText(
                            activity,
                            "دانلود ناموفق بود. اینترنت را بررسی کنید و دوباره تلاش کنید.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    /** صفحه‌ی نصب‌کننده‌ی اندروید را مستقیم باز می‌کند (بدون پنجره‌ی انتخاب برنامه). */
    @Suppress("DEPRECATION")
    private fun launchApkInstall(activity: Activity, file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val install = android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(install)
            } catch (e: Exception) {
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(view)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "باز کردن صفحه‌ی نصب ناموفق بود", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * فایل رو دانلود می‌کنه (تو حافظه‌ی موقت خودِ اپ) و بعد با اپ نصب‌شده‌ی مناسب
     * گوشی (پلیر موزیک برای mp3، پلیر ویدیو برای mp4/mkv و...) بازش می‌کنه — چون
     * بعضی سرورها (مثل گیت‌هاب) نوع فایل رو اشتباه اعلام می‌کنن و باز کردن مستقیم
     * لینک ریموت باعث میشه گوشی نتونه اپ مناسب رو پیدا کنه.
     */
    fun downloadAndOpenExternally(context: Context, url: String, title: String, mimeType: String) {
        Toast.makeText(context, "در حال آماده‌سازی فایل…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                val shareDir = java.io.File(context.cacheDir, "share")
                if (!shareDir.exists()) shareDir.mkdirs()
                val safeName = title.ifBlank { "kafinet_file" }.replace(Regex("[^A-Za-z0-9آ-ی_\\- ]"), "_")
                val file = java.io.File(shareDir, safeName + guessExtension(url))

                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context is Activity) {
                    context.runOnUiThread {
                        try {
                            context.startActivity(android.content.Intent.createChooser(viewIntent, title))
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    context.startActivity(viewIntent)
                }
            } catch (e: Exception) {
                if (context is Activity) {
                    context.runOnUiThread {
                        Toast.makeText(context, "دانلود یا باز کردن فایل ناموفق بود", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }
}
