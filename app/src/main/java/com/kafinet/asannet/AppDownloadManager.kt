package com.kafinet.asannet

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

data class DownloadRecord(
    val downloadId: Long,
    val title: String,
    val url: String,
    val timestamp: Long
)

enum class DownloadState { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, UNKNOWN }

data class DownloadStatus(
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long
)

/**
 * لایه‌ی داخلی رو موتور دانلود سیستم (DownloadManager) که فهرست همه‌ی دانلودهای
 * انجام‌شده تو اپ رو (صرف‌نظر از این‌که از کدوم صفحه شروع شده) نگه می‌داره، تا صفحه‌ی
 * «دانلودها» بتونه همیشه نشونشون بده — حتی بعد از بستن و باز کردن دوباره‌ی اپ.
 */
object AppDownloadManager {

    private const val PREFS_NAME = "kafinet_downloads"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 300

    fun registerDownload(context: Context, downloadId: Long, title: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString(KEY_RECORDS, "[]"))
        val obj = JSONObject()
        obj.put("id", downloadId)
        obj.put("title", title)
        obj.put("url", url)
        obj.put("time", System.currentTimeMillis())

        // اگه لیست خیلی طولانی شد، قدیمی‌ترین‌ها رو کنار می‌ذاریم
        val trimmed = JSONArray()
        val startFrom = if (array.length() >= MAX_RECORDS) array.length() - MAX_RECORDS + 1 else 0
        for (i in startFrom until array.length()) trimmed.put(array.get(i))
        trimmed.put(obj)

        prefs.edit().putString(KEY_RECORDS, trimmed.toString()).apply()
    }

    fun getAllRecords(context: Context): List<DownloadRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString(KEY_RECORDS, "[]"))
        val list = mutableListOf<DownloadRecord>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(
                DownloadRecord(
                    downloadId = obj.optLong("id"),
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    timestamp = obj.optLong("time")
                )
            )
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun removeRecord(context: Context, downloadId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString(KEY_RECORDS, "[]"))
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optLong("id") != downloadId) newArray.put(obj)
        }
        prefs.edit().putString(KEY_RECORDS, newArray.toString()).apply()
    }

    fun queryStatus(context: Context, downloadId: Long): DownloadStatus {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            manager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    val statusInt = if (statusCol >= 0) cursor.getInt(statusCol) else -1
                    val state = when (statusInt) {
                        DownloadManager.STATUS_PENDING -> DownloadState.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadState.SUCCESSFUL
                        DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                        else -> DownloadState.UNKNOWN
                    }
                    return DownloadStatus(
                        state = state,
                        bytesDownloaded = if (downloadedCol >= 0) cursor.getLong(downloadedCol) else 0L,
                        bytesTotal = if (totalCol >= 0) cursor.getLong(totalCol) else 0L
                    )
                }
            }
        } catch (e: Exception) {
            // نادیده گرفته می‌شه؛ پایین UNKNOWN برمی‌گرده
        }
        return DownloadStatus(DownloadState.UNKNOWN, 0L, 0L)
    }

    /** دانلودی که هنوز در حال انجامه رو واقعاً لغو می‌کنه (نه فقط از لیست اپ حذفش می‌کنه). */
    fun cancelDownload(context: Context, downloadId: Long) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
        } catch (e: Exception) {
            // اگه از قبل تو سیستم وجود نداشت، مشکلی نیست
        }
    }

    /** فایل دانلودشده رو با اپ مناسب (بر اساس نوعش) باز می‌کنه. */
    fun openDownload(context: Context, downloadId: Long) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
                return
            }
            val mime = manager.getMimeTypeForDownloadedFile(downloadId) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.err_app_not_installed, Toast.LENGTH_SHORT).show()
        }
    }
}
