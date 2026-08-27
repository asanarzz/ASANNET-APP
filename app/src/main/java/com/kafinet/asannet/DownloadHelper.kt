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

    /** یک data URI (مثل data:application/pdf;base64,...) را در پوشه‌ی عمومی Downloads ذخیره می‌کند. */
    fun saveDataUri(context: Context, dataUri: String, suggestedName: String): Boolean {
        return try {
            if (!dataUri.startsWith("data:")) return false
            val commaIndex = dataUri.indexOf(',')
            if (commaIndex == -1) return false
            val header = dataUri.substring(5, commaIndex)
            val payload = dataUri.substring(commaIndex + 1)
            val isBase64 = header.contains("base64")
            val mimeType = header.substringBefore(";").ifBlank { "application/octet-stream" }

            val bytes: ByteArray = if (isBase64) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }

            val fileName = suggestedName.ifBlank { "kafinet_file" }

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
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = java.io.File(downloadsDir, fileName)
                java.io.FileOutputStream(file).use { it.write(bytes) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
