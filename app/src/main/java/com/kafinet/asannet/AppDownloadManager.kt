package com.kafinet.asannet

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class DownloadRecord(
    val downloadId: Long,
    val title: String,
    val url: String,
    val timestamp: Long,
    /** true یعنی با موتور دانلودِ خود برنامه (AppDownloadService) دانلود می‌شه، نه دانلودر سیستم. */
    val isApp: Boolean = false,
    val mime: String = "",
    val uri: String = "",
    val path: String = ""
)

/** LISTED = تو لیست دانلود اضافه شده ولی هنوز کاربر شروعش نکرده. */
enum class DownloadState { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, UNKNOWN, LISTED }

data class DownloadStatus(
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    /** کد «دلیل» دانلودر اندروید: برای متوقف‌شده کد مکث، برای ناموفق کد خطا یا کد HTTP. */
    val reason: Int = 0,
    /** متن آماده‌ی فارسی برای دانلودهای موتور خود برنامه؛ برای دانلودر سیستم خالیه. */
    val message: String = ""
)

/**
 * فهرست همه‌ی دانلودهای انجام‌شده تو اپ (صرف‌نظر از این‌که از کدوم صفحه شروع شده) رو نگه
 * می‌داره تا صفحه‌ی «دانلودها» همیشه بتونه نشونشون بده — حتی بعد از بستن و باز کردن اپ.
 * دانلودهای قدیمی (دانلودر سیستم) و جدید (AppDownloadService) هر دو اینجا هستن.
 */
object AppDownloadManager {

    private const val PREFS_NAME = "kafinet_downloads"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 300

    private val lock = Any()

    /** وضعیت لحظه‌ایِ دانلودهای در حال انجام (فقط تو حافظه). */
    private val live = ConcurrentHashMap<Long, DownloadStatus>()

    fun setLive(downloadId: Long, status: DownloadStatus) {
        live[downloadId] = status
    }

    fun clearLive(downloadId: Long) {
        live.remove(downloadId)
    }

    private fun readArray(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString(KEY_RECORDS, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(context: Context, array: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun trimmedWith(array: JSONArray, obj: JSONObject): JSONArray {
        // اگه لیست خیلی طولانی شد، قدیمی‌ترین‌ها رو کنار می‌ذاریم
        val trimmed = JSONArray()
        val startFrom = if (array.length() >= MAX_RECORDS) array.length() - MAX_RECORDS + 1 else 0
        for (i in startFrom until array.length()) trimmed.put(array.get(i))
        trimmed.put(obj)
        return trimmed
    }

    /** ثبت دانلودی که با دانلودر سیستم شروع شده (روش قدیمی). */
    fun registerDownload(context: Context, downloadId: Long, title: String, url: String) {
        synchronized(lock) {
            val array = readArray(context)
            val obj = JSONObject()
            obj.put("id", downloadId)
            obj.put("title", title)
            obj.put("url", url)
            obj.put("time", System.currentTimeMillis())
            writeArray(context, trimmedWith(array, obj))
        }
    }

    /** ثبت دانلود جدید برای موتور خود برنامه. شناسه‌ی یکتای دانلود برگردونده می‌شه. */
    fun registerAppDownload(context: Context, title: String, url: String): Long {
        return synchronized(lock) {
            val array = readArray(context)
            val used = HashSet<Long>()
            for (i in 0 until array.length()) {
                used.add(array.optJSONObject(i)?.optLong("id") ?: 0L)
            }
            var id = System.currentTimeMillis()
            while (id in used) id++

            val obj = JSONObject()
            obj.put("id", id)
            obj.put("title", title)
            obj.put("url", url)
            obj.put("time", System.currentTimeMillis())
            obj.put("app", 1)
            obj.put("state", "LISTED")
            obj.put("dl", 0L)
            obj.put("total", 0L)
            obj.put("mime", AppDownloadStorage.mimeFor(title))
            obj.put("uri", "")
            obj.put("path", "")
            obj.put("err", "")
            obj.put("reason", 0)
            writeArray(context, trimmedWith(array, obj))
            id
        }
    }

    fun getAllRecords(context: Context): List<DownloadRecord> {
        val array = synchronized(lock) { readArray(context) }
        val list = mutableListOf<DownloadRecord>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(
                DownloadRecord(
                    downloadId = obj.optLong("id"),
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    timestamp = obj.optLong("time"),
                    isApp = obj.optInt("app", 0) == 1,
                    mime = obj.optString("mime"),
                    uri = obj.optString("uri"),
                    path = obj.optString("path")
                )
            )
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun getAppRecord(context: Context, downloadId: Long): DownloadRecord? {
        return getAllRecords(context).firstOrNull { it.downloadId == downloadId && it.isApp }
    }

    /** اگه همین لینک قبلاً تو لیست اضافه شده (یا نیمه‌کاره مونده)، همون رکورد رو برمی‌گردونه. */
    fun findOpenAppRecordByUrl(context: Context, url: String): DownloadRecord? {
        return getAllRecords(context).firstOrNull { record ->
            record.isApp && record.url == url && when (queryStatus(context, record.downloadId).state) {
                DownloadState.LISTED, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.RUNNING -> true
                else -> false
            }
        }
    }

    /** فیلدهای ذخیره‌شده‌ی یک دانلودِ موتور خود برنامه رو عوض می‌کنه. */
    fun updateAppFields(context: Context, downloadId: Long, block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val array = readArray(context)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optLong("id") == downloadId && obj.optInt("app", 0) == 1) {
                    block(obj)
                    writeArray(context, array)
                    return
                }
            }
        }
    }

    fun removeRecord(context: Context, downloadId: Long) {
        synchronized(lock) {
            val array = readArray(context)
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optLong("id") != downloadId) newArray.put(obj)
            }
            writeArray(context, newArray)
        }
        live.remove(downloadId)
    }

    private fun appJson(context: Context, downloadId: Long): JSONObject? {
        val array = synchronized(lock) { readArray(context) }
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optLong("id") == downloadId && obj.optInt("app", 0) == 1) return obj
        }
        return null
    }

    fun queryStatus(context: Context, downloadId: Long): DownloadStatus {
        // دانلودِ موتور خود برنامه
        val saved = appJson(context, downloadId)
        if (saved != null) {
            live[downloadId]?.let { return it }
            val downloaded = saved.optLong("dl")
            val total = saved.optLong("total")
            val err = saved.optString("err")
            val reason = saved.optInt("reason")
            return when (saved.optString("state", "LISTED")) {
                "SUCCESSFUL" -> DownloadStatus(DownloadState.SUCCESSFUL, downloaded, total, 0, "")
                "FAILED" -> DownloadStatus(
                    DownloadState.FAILED, downloaded, total, reason,
                    err.ifBlank { "ناموفق — با ▶ دوباره امتحان کن" }
                )
                "LISTED" -> DownloadStatus(
                    DownloadState.LISTED, downloaded, total, 0,
                    "تو لیست دانلود — برای شروع، دکمه‌ی ▶ رو بزن"
                )
                else -> DownloadStatus(
                    // «در حال دانلود» که سرویسش دیگه نیست (مثلاً اپ بسته شده) یعنی متوقف شده
                    DownloadState.PAUSED, downloaded, total, 0,
                    err.ifBlank { "متوقف‌شده — برای ادامه، دکمه‌ی ▶ رو بزن" }
                )
            }
        }

        // دانلودِ قدیمیِ دانلودر سیستم
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            manager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

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
                        bytesTotal = if (totalCol >= 0) cursor.getLong(totalCol) else 0L,
                        reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else 0
                    )
                }
            }
        } catch (e: Exception) {
            // نادیده گرفته می‌شه؛ پایین UNKNOWN برمی‌گرده
        }
        return DownloadStatus(DownloadState.UNKNOWN, 0L, 0L)
    }

    /**
     * توضیح فارسیِ وضعیت دانلود برای نمایش زیر هر ردیف تو صفحه‌ی «دانلودها».
     * (برای دانلودهای قدیمیِ دانلودر سیستم؛ دانلودهای جدید خودشون message دارن.)
     */
    fun describeProblem(status: DownloadStatus): String? {
        return when (status.state) {
            DownloadState.PENDING -> "در صف دانلودر سیستم (هنوز شروع نشده)"
            DownloadState.RUNNING ->
                if (status.bytesTotal <= 0L) "در حال اتصال به سرور (هنوز حجم فایل مشخص نشده)" else null
            DownloadState.PAUSED -> when (status.reason) {
                DownloadManager.PAUSED_WAITING_TO_RETRY -> "مکث: سرور جواب نداد، بعداً دوباره تلاش می‌شود"
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "مکث: منتظر اتصال به اینترنت"
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "مکث: فایل بزرگه و منتظر Wi-Fi است"
                else -> "مکث: دلیل نامشخص (کد " + status.reason + ")"
            }
            DownloadState.FAILED -> when (status.reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "خطا: جای کافی تو حافظه نیست"
                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "خطا: فایلی با همین اسم از قبل هست"
                DownloadManager.ERROR_FILE_ERROR -> "خطا: ذخیره‌ی فایل روی حافظه ممکن نشد"
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "خطا: حافظه‌ی ذخیره‌سازی در دسترس نیست"
                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "خطا: لینک بیش از حد هدایت می‌شود"
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "خطا: ارتباط با سرور وسط دانلود قطع شد"
                DownloadManager.ERROR_CANNOT_RESUME -> "خطا: امکان ادامه‌ی دانلود نیست"
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "خطا: سرور جواب نامعتبر داد"
                in 400..599 -> "خطای سرور (کد " + status.reason + ")" +
                    if (status.reason == 403) " — سرور اجازه‌ی دانلود از داخل برنامه رو نمی‌ده"
                    else if (status.reason == 404) " — فایل روی سرور پیدا نشد"
                    else ""
                else -> "خطای نامشخص (کد " + status.reason + ")"
            }
            else -> null
        }
    }

    /**
     * دانلودی که هنوز تموم نشده رو واقعاً لغو می‌کنه (نه فقط از لیست اپ حذفش می‌کنه).
     * برای دانلودهای موتور خود برنامه، فایل نیمه‌کاره هم پاک می‌شه.
     */
    fun cancelDownload(context: Context, downloadId: Long) {
        val appRecord = getAppRecord(context, downloadId)
        if (appRecord != null) {
            AppDownloadService.cancel(context, downloadId, appRecord.uri, appRecord.path)
            return
        }
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
            val appRecord = getAppRecord(context, downloadId)
            val uri: Uri?
            val mime: String
            if (appRecord != null) {
                uri = if (appRecord.uri.isNotBlank()) Uri.parse(appRecord.uri) else null
                mime = appRecord.mime.ifBlank { "*/*" }
            } else {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                uri = manager.getUriForDownloadedFile(downloadId)
                mime = manager.getMimeTypeForDownloadedFile(downloadId) ?: "*/*"
            }
            if (uri == null) {
                Toast.makeText(context, R.string.error_loading, Toast.LENGTH_SHORT).show()
                return
            }
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
