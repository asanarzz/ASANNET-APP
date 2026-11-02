package com.kafinet.asannet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * ذخیره‌ی فایل‌های دانلودشده‌ی برنامه: همه‌چیز تو پوشه‌ی «آسان نت» داخل Downloads گوشی.
 * - اندروید ۱۰ و بالاتر: با MediaStore (بدون نیاز به هیچ اجازه‌ای)
 * - اندروید ۹ و پایین‌تر: مستقیم تو Download/آسان نت (با اجازه‌ی ذخیره‌سازی)
 */
object AppDownloadStorage {

    const val FOLDER_NAME = "آسان نت"

    /** دقیقاً یکی از دو مقدار پر است: uri (اندروید ۱۰+) یا file (اندروید ۹ و پایین‌تر). */
    class Target(val uri: Uri?, val file: File?)

    /** فقط کاراکترهای ممنوعِ فایل‌سیستم عوض می‌شن؛ فارسی و عدد دست نمی‌خورن. */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimEnd('.')
        return cleaned.ifBlank { "kafinet_file" }.take(120)
    }

    fun mimeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        val known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (known != null) return known
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    fun create(context: Context, fileName: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("ساخت فایل تو حافظه ممکن نشد")
            return Target(uri, null)
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("ساخت پوشه‌ی آسان نت ممکن نشد")
        return Target(null, uniqueFile(dir, fileName))
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, base + " (" + i + ")" + ext)
            i++
        }
        return candidate
    }

    /** از روی چیزی که تو رکورد ذخیره شده، مقصد رو برمی‌گردونه؛ اگه چیزی ذخیره نشده null. */
    fun fromRecord(uri: String, path: String): Target? {
        if (path.isNotBlank()) return Target(null, File(path))
        if (uri.startsWith("content://")) return Target(Uri.parse(uri), null)
        return null
    }

    /** حجم فعلیِ فایل (برای ادامه‌ی دانلود)، یا -1 اگه فایل دیگه وجود نداره. */
    fun size(context: Context, target: Target): Long {
        return try {
            val uri = target.uri
            val file = target.file
            if (uri != null) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } else if (file != null) {
                if (file.exists()) file.length() else -1L
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    fun openOutput(context: Context, target: Target, append: Boolean): OutputStream {
        val uri = target.uri
        val file = target.file
        if (uri != null) {
            return context.contentResolver.openOutputStream(uri, if (append) "wa" else "wt")
                ?: throw IOException("باز کردن فایل برای نوشتن ممکن نشد")
        }
        if (file != null) return FileOutputStream(file, append)
        throw IOException("مقصد ذخیره نامعتبره")
    }

    /**
     * دانلود تموم شده: فایل از حالت «در حال ساخت» (pending) درمیاد و تو گالری/فایل‌ها دیده می‌شه.
     * خروجی: آدرس uri فایل (برای اندروید ۹ و پایین‌تر بعداً از طریق onScanned میاد).
     */
    fun finish(context: Context, target: Target, mime: String, onScanned: (String) -> Unit): String {
        val uri = target.uri
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }
            return uri.toString()
        }
        val file = target.file ?: return ""
        MediaScannerConnection.scanFile(
            context.applicationContext, arrayOf(file.absolutePath), arrayOf(mime)
        ) { _, scanned -> if (scanned != null) onScanned(scanned.toString()) }
        return ""
    }

    fun delete(context: Context, target: Target) {
        try {
            val uri = target.uri
            val file = target.file
            if (uri != null) context.contentResolver.delete(uri, null, null)
            if (file != null && file.exists()) file.delete()
        } catch (e: Exception) {
            // اگه از قبل پاک شده بود مشکلی نیست
        }
    }
}

/**
 * موتور دانلودِ خود برنامه (به‌جای دانلودر سیستم اندروید):
 * - هر دانلود جدا، با امکان توقف و ادامه از همون‌جا (Range)
 * - درخواست مثل مرورگر (User-Agent کروم + دنبال‌کردن ریدایرکت‌ها)
 * - نوتیفیکیشن پیشرفت؛ با بستن برنامه هم ادامه پیدا می‌کنه
 * هیچ دانلودی خودکار شروع نمی‌شه؛ فقط وقتی کاربر دکمه‌ی دانلود یا ▶ رو بزنه.
 */
class AppDownloadService : Service() {

    companion object {
        private const val ACTION_START = "com.kafinet.asannet.dl.START"
        private const val ACTION_PAUSE = "com.kafinet.asannet.dl.PAUSE"
        private const val ACTION_CANCEL = "com.kafinet.asannet.dl.CANCEL"
        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_PATH = "extra_path"
        private const val CHANNEL_ID = "app_downloads"
        private const val NOTIFICATION_ID = 503
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** شروع یا ادامه‌ی یک دانلود (دقیقاً از همون‌جایی که مونده). */
        fun start(context: Context, id: Long) {
            val intent = Intent(context, AppDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, id: Long) {
            val intent = Intent(context, AppDownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_ID, id)
            context.startService(intent)
        }

        /** لغو کامل: دانلود متوقف و فایل نیمه‌کاره پاک می‌شه. */
        fun cancel(context: Context, id: Long, uri: String, path: String) {
            val intent = Intent(context, AppDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_PATH, path)
            context.startService(intent)
        }
    }

    private class DlTask(val id: Long) {
        @Volatile var pause = false
        @Volatile var cancel = false
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var title = ""
        @Volatile var downloaded = 0L
        @Volatile var total = 0L
    }

    private class HttpFail(val code: Int) : IOException("HTTP $code")

    private val tasks = ConcurrentHashMap<Long, DlTask>()
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L

        if (action == ACTION_START) {
            // قانون اندروید: بعد از startForegroundService باید فوراً startForeground صدا زده بشه
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification())
            if (id > 0L && !tasks.containsKey(id)) {
                val task = DlTask(id)
                tasks[id] = task
                acquireWakeLock()
                Thread { runTask(task) }.start()
            }
        } else if (action == ACTION_PAUSE) {
            tasks[id]?.let { requestStop(it, cancel = false) }
        } else if (action == ACTION_CANCEL) {
            val task = tasks[id]
            if (task != null) {
                requestStop(task, cancel = true)
            } else {
                val target = AppDownloadStorage.fromRecord(
                    intent?.getStringExtra(EXTRA_URI).orEmpty(),
                    intent?.getStringExtra(EXTRA_PATH).orEmpty()
                )
                if (target != null) AppDownloadStorage.delete(this, target)
                AppDownloadManager.clearLive(id)
            }
        }

        if (tasks.isEmpty()) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun requestStop(task: DlTask, cancel: Boolean) {
        if (cancel) task.cancel = true else task.pause = true
        // بستن اتصال باید روی تردِ جدا باشه (روی تردِ اصلی ممنوعه)
        Thread {
            try {
                task.conn?.disconnect()
            } catch (e: Exception) {
                // بی‌اهمیت
            }
        }.start()
    }

    // ───────────────────────── خودِ دانلود ─────────────────────────

    private fun runTask(task: DlTask) {
        val id = task.id
        var sink: AppDownloadStorage.Target? = null
        var downloaded = 0L
        var total = 0L
        try {
            val rec = AppDownloadManager.getAppRecord(this, id) ?: return
            task.title = rec.title
            val mime = rec.mime.ifBlank { AppDownloadStorage.mimeFor(rec.title) }

            // ادامه از فایل نیمه‌کاره (اگه هنوز هست) یا ساخت فایل جدید
            val reuse = AppDownloadStorage.fromRecord(rec.uri, rec.path)
            val reuseSize = if (reuse != null) AppDownloadStorage.size(this, reuse) else -1L
            val activeSink: AppDownloadStorage.Target
            var existing: Long
            if (reuse != null && reuseSize >= 0L) {
                activeSink = reuse
                existing = reuseSize
            } else {
                val created = AppDownloadStorage.create(this, rec.title, mime)
                activeSink = created
                existing = 0L
                AppDownloadManager.updateAppFields(this, id) {
                    it.put("uri", created.uri?.toString() ?: "")
                    it.put("path", created.file?.absolutePath ?: "")
                }
            }
            sink = activeSink
            downloaded = existing
            task.downloaded = downloaded
            publish(id, DownloadState.RUNNING, downloaded, 0L, "در حال اتصال به سرور…")
            main.post { updateNotification() }

            var append = existing > 0L
            var conn = connect(task, rec.url, existing)
            var code = conn.responseCode
            var alreadyComplete = false

            if (code == 416 && existing > 0L) {
                // درخواست ادامه از جایی که از انتهای فایل بیشتره
                val serverTotal = parseTotal(conn.getHeaderField("Content-Range"))
                conn.disconnect()
                if (serverTotal > 0L && existing >= serverTotal) {
                    alreadyComplete = true
                    total = serverTotal
                } else {
                    existing = 0L
                    downloaded = 0L
                    append = false
                    conn = connect(task, rec.url, 0L)
                    code = conn.responseCode
                }
            }

            if (!alreadyComplete) {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw HttpFail(code)
                }
                if (code == 206) {
                    val fromHeader = parseTotal(conn.getHeaderField("Content-Range"))
                    total = if (fromHeader > 0L) fromHeader else existing + conn.contentLengthLong
                    append = true
                } else {
                    // سرور ادامه‌ی دانلود رو پشتیبانی نمی‌کنه (یا ازش نخواستیم): از اول می‌گیریم
                    existing = 0L
                    downloaded = 0L
                    append = false
                    total = conn.contentLengthLong
                }
                if (total < 0L) total = 0L
                task.total = total
                task.downloaded = downloaded
                publish(id, DownloadState.RUNNING, downloaded, total, "در حال دانلود…")

                var lastLive = 0L
                var lastPersist = 0L
                var lastNotif = 0L
                val buffer = ByteArray(64 * 1024)
                try {
                    conn.inputStream.use { input ->
                        AppDownloadStorage.openOutput(this, activeSink, append).use { output ->
                            while (!task.pause && !task.cancel) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                downloaded += n
                                task.downloaded = downloaded
                                val now = System.currentTimeMillis()
                                if (now - lastLive > 300L) {
                                    lastLive = now
                                    publish(id, DownloadState.RUNNING, downloaded, total, "در حال دانلود…")
                                }
                                if (now - lastPersist > 2500L) {
                                    lastPersist = now
                                    val d = downloaded
                                    val t = total
                                    AppDownloadManager.updateAppFields(this, id) {
                                        it.put("state", "RUNNING")
                                        it.put("dl", d)
                                        it.put("total", t)
                                    }
                                }
                                if (now - lastNotif > 1000L) {
                                    lastNotif = now
                                    main.post { updateNotification() }
                                }
                            }
                            output.flush()
                        }
                    }
                } catch (e: IOException) {
                    // اگه خودمون اتصال رو بستیم (توقف/لغو) خطا نیست؛ وگرنه بالا گزارش می‌شه
                    if (!task.pause && !task.cancel) throw e
                }
                task.conn = null
                try {
                    conn.disconnect()
                } catch (e: Exception) {
                    // بی‌اهمیت
                }
            }

            if (task.cancel) {
                AppDownloadStorage.delete(this, activeSink)
                AppDownloadManager.removeRecord(this, id)
                AppDownloadManager.clearLive(id)
                return
            }
            if (task.pause) {
                persist(id, "PAUSED", downloaded, total, "متوقف‌شده — برای ادامه، دکمه‌ی ▶ رو بزن", 0)
                return
            }
            if (total > 0L && downloaded < total) {
                throw IOException("incomplete download")
            }

            // موفق
            val finalSize = if (total > 0L) total else downloaded
            val uriString = AppDownloadStorage.finish(this, activeSink, mime) { scanned ->
                AppDownloadManager.updateAppFields(this, id) { it.put("uri", scanned) }
            }
            AppDownloadManager.updateAppFields(this, id) {
                it.put("state", "SUCCESSFUL")
                it.put("dl", finalSize)
                it.put("total", finalSize)
                it.put("err", "")
                it.put("reason", 0)
                if (uriString.isNotBlank()) it.put("uri", uriString)
            }
            AppDownloadManager.clearLive(id)
            notifyResult(id, task.title, true)
        } catch (e: Exception) {
            if (task.cancel) {
                sink?.let { AppDownloadStorage.delete(this, it) }
                AppDownloadManager.removeRecord(this, id)
                AppDownloadManager.clearLive(id)
            } else if (task.pause) {
                persist(id, "PAUSED", downloaded, total, "متوقف‌شده — برای ادامه، دکمه‌ی ▶ رو بزن", 0)
            } else {
                if (downloaded <= 0L && sink != null) {
                    // چیزی دانلود نشده؛ فایل خالی نمی‌مونه
                    AppDownloadStorage.delete(this, sink)
                    AppDownloadManager.updateAppFields(this, id) {
                        it.put("uri", "")
                        it.put("path", "")
                    }
                }
                val reason = if (e is HttpFail) e.code else 0
                persist(id, "FAILED", downloaded, total, describeError(e), reason)
                notifyResult(id, task.title, false)
            }
        } finally {
            task.conn = null
            tasks.remove(id)
            main.post { onTaskEnded() }
        }
    }

    private fun connect(task: DlTask, startUrl: String, rangeStart: Long): HttpURLConnection {
        var current = startUrl
        for (attempt in 0..8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart > 0L) c.setRequestProperty("Range", "bytes=" + rangeStart + "-")
            task.conn = c
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location")
                current = URL(URL(current), location).toString()
                continue
            }
            return c
        }
        throw IOException("too many redirects")
    }

    /** از هدر Content-Range مثل «bytes 100-999/1000» عدد کل (1000) رو درمیاره. */
    private fun parseTotal(header: String?): Long {
        if (header == null) return -1L
        return header.substringAfterLast('/').trim().toLongOrNull() ?: -1L
    }

    private fun describeError(e: Exception): String {
        return when (e) {
            is HttpFail -> {
                val detail = when (e.code) {
                    403 -> "سرور اجازه‌ی دانلود نداد (کد 403)"
                    404 -> "فایل روی سرور پیدا نشد (کد 404)"
                    else -> "خطای سرور (کد " + e.code + ")"
                }
                "ناموفق: " + detail
            }
            is UnknownHostException -> "ناموفق: آدرس سرور پیدا نشد یا اینترنت قطعه — با ▶ دوباره امتحان کن"
            is SocketTimeoutException -> "ناموفق: سرور جواب نداد (تایم‌اوت) — با ▶ دوباره امتحان کن"
            is javax.net.ssl.SSLException -> "ناموفق: خطای اتصال امن (SSL) — با ▶ دوباره امتحان کن"
            is IOException -> {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("No space", ignoreCase = true) || msg.contains("ENOSPC") ->
                        "ناموفق: جای کافی تو حافظه نیست"
                    msg.contains("incomplete") ->
                        "ارتباط وسط دانلود قطع شد — با ▶ از همون‌جا ادامه بده"
                    else -> "ناموفق: " + e.javaClass.simpleName + " — با ▶ دوباره امتحان کن"
                }
            }
            else -> "ناموفق: " + e.javaClass.simpleName
        }
    }

    private fun publish(id: Long, state: DownloadState, downloaded: Long, total: Long, message: String) {
        AppDownloadManager.setLive(id, DownloadStatus(state, downloaded, total, 0, message))
    }

    /** وضعیت نهایی (متوقف/ناموفق) رو ذخیره می‌کنه و وضعیت زنده رو پاک می‌کنه. */
    private fun persist(id: Long, state: String, downloaded: Long, total: Long, message: String, reason: Int) {
        AppDownloadManager.updateAppFields(this, id) {
            it.put("state", state)
            it.put("dl", downloaded)
            it.put("total", total)
            it.put("err", message)
            it.put("reason", reason)
        }
        AppDownloadManager.clearLive(id)
    }

    // ───────────────────────── نوتیفیکیشن ─────────────────────────

    private fun onTaskEnded() {
        if (tasks.isEmpty()) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun updateNotification() {
        if (tasks.isEmpty()) return
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // بی‌اهمیت
        }
    }

    private fun openDownloadsIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val list = tasks.values.toList()
        var text = "در حال آماده‌سازی…"
        var progress = 0
        var indeterminate = true
        if (list.size == 1) {
            val t = list[0]
            if (t.total > 0L) {
                progress = (t.downloaded * 100 / t.total).toInt().coerceIn(0, 100)
                indeterminate = false
                text = t.title + " — " + progress + "٪"
            } else {
                text = t.title
            }
        } else if (list.size > 1) {
            text = list.size.toString() + " دانلود در حال انجام"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("آسان نت — دانلود")
            .setContentText(text)
            .setContentIntent(openDownloadsIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    private fun notifyResult(id: Long, title: String, success: Boolean) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(if (success) "دانلود کامل شد" else "دانلود ناموفق بود")
                .setContentText(
                    if (success) title + " — پوشه‌ی Downloads/" + AppDownloadStorage.FOLDER_NAME else title
                )
                .setContentIntent(openDownloadsIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(8000 + (id % 1000L).toInt(), notification)
        } catch (e: Exception) {
            // بی‌اهمیت
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "دانلودها", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kafinet:download").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // بی‌اهمیت
        }
        wakeLock = null
    }

    override fun onDestroy() {
        tasks.values.forEach { it.pause = true }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
