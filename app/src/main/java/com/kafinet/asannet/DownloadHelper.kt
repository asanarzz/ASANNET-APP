package com.kafinet.asannet

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
            manager.enqueue(request)
            Toast.makeText(context, "در حال دانلود در پوشه‌ی Downloads…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessExtension(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#")
        val lastDot = clean.lastIndexOf('.')
        val lastSlash = clean.lastIndexOf('/')
        return if (lastDot > lastSlash && lastDot != -1) clean.substring(lastDot) else ""
    }
}
