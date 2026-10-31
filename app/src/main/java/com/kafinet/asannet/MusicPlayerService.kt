package com.kafinet.asannet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * پخش‌کننده‌ی مستقل موزیک — کاملاً جدا از RadioPlayerService (رادیو).
 *
 * هر آهنگ اول کامل دانلود می‌شه (با اطلاع پیشرفت به صفحه‌ی پلیر) و بعد از روی خود گوشی
 * پخش می‌شه. تو حالت «پخش همه»، موقع پخش آهنگ فعلی، آهنگ بعدی هم از قبل دانلود می‌شه
 * تا بین آهنگ‌ها وقفه نیفته. تو حالت «یکی‌یکی»، بعد از تموم‌شدن آهنگ پخش متوقف می‌شه.
 *
 * رادیو دست نمی‌خوره: فقط وقتی پخش موزیک شروع می‌شه، اگه رادیو روشن باشه، با همون
 * دستور «توقف» خودش خاموشش می‌کنیم تا دو صدا هم‌زمان پخش نشن.
 */
class MusicPlayerService : Service() {

    companion object {
        const val ACTION_START = "com.kafinet.asannet.music.START"
        const val ACTION_TOGGLE = "com.kafinet.asannet.music.TOGGLE"
        const val ACTION_NEXT = "com.kafinet.asannet.music.NEXT"
        const val ACTION_PREV = "com.kafinet.asannet.music.PREV"
        const val ACTION_STOP = "com.kafinet.asannet.music.STOP"
        const val ACTION_SET_MODE = "com.kafinet.asannet.music.SET_MODE"
        const val EXTRA_TITLES = "extra_titles"
        const val EXTRA_URLS = "extra_urls"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_PLAY_ALL = "extra_play_all"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 502

        @Volatile var isActive = false
            private set
        /** بین لحظه‌ی درخواست شروع سرویس تا لحظه‌ای که واقعاً بالا بیاد true است. */
        @Volatile var starting = false
            private set
        @Volatile var queueTitles: List<String> = emptyList()
            private set
        @Volatile var currentIndex = 0
            private set
        @Volatile var isPlayingNow = false
            private set
        @Volatile var isPreparing = false
            private set
        @Volatile var downloadedBytes = 0L
            private set
        @Volatile var totalBytes = 0L
            private set
        @Volatile var playAll = true
            private set
        @Volatile var errorText: String? = null
            private set
        @Volatile var currentPlayer: ExoPlayer? = null
            private set

        /** صفحه‌ی پلیر اینو ست می‌کنه تا با هر تغییر وضعیت خبردار بشه (همیشه روی ترد اصلی). */
        @Volatile var stateListener: (() -> Unit)? = null

        fun start(context: Context, titles: List<String>, urls: List<String>, startIndex: Int, playAll: Boolean) {
            starting = true
            Handler(Looper.getMainLooper()).postDelayed({ starting = false }, 6000)
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                putExtra(EXTRA_INDEX, startIndex)
                putExtra(EXTRA_PLAY_ALL, playAll)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private class DlTask(val url: String, val target: File) {
        val cancelled = AtomicBoolean(false)
        val progressCallbacks = ArrayList<(Long, Long) -> Unit>()
        val doneCallbacks = ArrayList<(File?) -> Unit>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tasks = HashMap<String, DlTask>()
    private var queueUrls: List<String> = emptyList()
    private var exo: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var playToken = 0
    private lateinit var cacheRoot: File

    override fun onCreate() {
        super.onCreate()
        cacheRoot = File(cacheDir, "music_dl")
        if (!cacheRoot.exists()) cacheRoot.mkdirs()
        mediaSession = MediaSessionCompat(this, "KafinetMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = togglePlayPause()
                override fun onPause() = togglePlayPause()
                override fun onSkipToNext() = goNext()
                override fun onSkipToPrevious() = goPrevious()
                override fun onStop() = stopAll()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // اول باید فوراً startForeground صدا زده بشه (قانون اندروید برای startForegroundService)
                createChannelIfNeeded()
                val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
                val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
                if (urls.isEmpty() || titles.size != urls.size) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    stopAll()
                    return START_NOT_STICKY
                }
                queueTitles = titles
                queueUrls = urls
                playAll = intent.getBooleanExtra(EXTRA_PLAY_ALL, true)
                currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1)
                isActive = true
                starting = false
                stopRadioIfPlaying()
                startForeground(NOTIFICATION_ID, buildNotification())
                playIndex(currentIndex)
            }
            ACTION_TOGGLE -> if (isActive) togglePlayPause()
            ACTION_NEXT -> if (isActive) goNext()
            ACTION_PREV -> if (isActive) goPrevious()
            ACTION_SET_MODE -> if (isActive) {
                playAll = intent.getBooleanExtra(EXTRA_PLAY_ALL, true)
                if (playAll && exo?.playbackState == Player.STATE_READY) prefetchNext()
                publishState()
            }
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    /** رادیو رو فقط با همان دستور «توقف» خودش خاموش می‌کنیم؛ به کد رادیو دست نمی‌زنیم. */
    private fun stopRadioIfPlaying() {
        try {
            if (RadioPlayerService.currentPlayer != null || RadioPlayerService.isPlayingNow) {
                startService(
                    Intent(this, RadioPlayerService::class.java).setAction(RadioPlayerService.ACTION_STOP)
                )
            }
        } catch (e: Exception) {
            // بی‌اهمیت: حداقل فوکوس صدا باعث می‌شه رادیو خودش مکث کنه
        }
    }

    // ───────────────────────── صف پخش و دانلود ─────────────────────────

    private fun cacheFileFor(url: String): File {
        val clean = url.substringBefore("?").substringBefore("#")
        val dot = clean.lastIndexOf('.')
        val slash = clean.lastIndexOf('/')
        val ext = if (dot > slash && dot != -1 && clean.length - dot <= 6) clean.substring(dot) else ".mp3"
        return File(cacheRoot, "m_" + Integer.toHexString(url.hashCode()) + ext)
    }

    private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

    private fun playIndex(index: Int) {
        if (index !in queueUrls.indices) return
        playToken++
        val token = playToken
        currentIndex = index
        errorText = null
        isPlayingNow = false
        isPreparing = true
        downloadedBytes = 0L
        totalBytes = 0L
        try {
            exo?.stop()
            exo?.clearMediaItems()
        } catch (e: Exception) {
            // بی‌اهمیت
        }

        val url = queueUrls[index]
        val nextUrl = queueUrls.getOrNull(index + 1)
        cancelTasksExcept(setOfNotNull(url, nextUrl))
        cleanupCache(index)
        publishState()

        if (!isHttp(url)) {
            // فایل محلی/asset: نیازی به دانلود نیست
            val uri = if (url.startsWith("file:///android_asset/")) {
                Uri.parse("asset:///" + url.removePrefix("file:///android_asset/"))
            } else {
                Uri.parse(url)
            }
            startPlaybackUri(uri)
            return
        }

        ensureFile(
            url,
            onProgress = { done, total ->
                if (token == playToken) {
                    downloadedBytes = done
                    totalBytes = total
                    stateListener?.invoke()
                }
            },
            onDone = { file ->
                if (token == playToken) {
                    if (file == null) onDownloadFailed(index) else startPlaybackUri(Uri.fromFile(file))
                }
            }
        )
    }

    private fun ensureFile(url: String, onProgress: ((Long, Long) -> Unit)?, onDone: (File?) -> Unit) {
        val target = cacheFileFor(url)
        if (target.exists() && target.length() > 0L) {
            onDone(target)
            return
        }
        val existing = tasks[url]
        if (existing != null) {
            onProgress?.let { existing.progressCallbacks.add(it) }
            existing.doneCallbacks.add(onDone)
            return
        }
        val task = DlTask(url, target)
        onProgress?.let { task.progressCallbacks.add(it) }
        task.doneCallbacks.add(onDone)
        tasks[url] = task

        Thread {
            val part = File(cacheRoot, target.name + "." + System.nanoTime() + ".part")
            var ok = false
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP " + connection.responseCode)
                }
                val total = connection.contentLength.toLong()
                var downloaded = 0L
                var lastUpdate = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (!task.cancelled.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 200) {
                                lastUpdate = now
                                val d = downloaded
                                handler.post {
                                    if (!task.cancelled.get()) {
                                        task.progressCallbacks.toList().forEach { cb -> cb(d, total) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!task.cancelled.get()) {
                    if (total > 0 && downloaded < total) throw IOException("incomplete download")
                    if (target.exists()) target.delete()
                    ok = part.renameTo(target)
                }
            } catch (e: Exception) {
                ok = false
            } finally {
                if (!ok) {
                    try {
                        part.delete()
                    } catch (ignored: Exception) {
                    }
                }
            }
            val success = ok
            handler.post {
                if (tasks[url] === task) tasks.remove(url)
                if (!task.cancelled.get()) {
                    task.doneCallbacks.toList().forEach { cb -> cb(if (success) target else null) }
                }
            }
        }.start()
    }

    private fun cancelTasksExcept(keep: Set<String>) {
        tasks.values.toList().forEach { task ->
            if (task.url !in keep) {
                task.cancelled.set(true)
                tasks.remove(task.url)
            }
        }
    }

    /** فقط آهنگ قبلی، فعلی و بعدی تو حافظه‌ی موقت می‌مونن؛ بقیه پاک می‌شن. */
    private fun cleanupCache(index: Int) {
        val keepNames = listOf(index - 1, index, index + 1)
            .mapNotNull { queueUrls.getOrNull(it) }
            .filter { isHttp(it) }
            .map { cacheFileFor(it).name }
        cacheRoot.listFiles()?.forEach { f ->
            if (keepNames.none { f.name.startsWith(it) }) {
                try {
                    f.delete()
                } catch (e: Exception) {
                    // بی‌اهمیت
                }
            }
        }
    }

    private fun prefetchNext() {
        if (!playAll) return
        val nextUrl = queueUrls.getOrNull(currentIndex + 1) ?: return
        if (!isHttp(nextUrl)) return
        ensureFile(nextUrl, null) { /* فقط پیش‌دانلود؛ نتیجه مهم نیست */ }
    }

    private fun onDownloadFailed(index: Int) {
        isPreparing = false
        val message = "دانلود آهنگ ناموفق بود"
        errorText = message
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        publishState()
        advanceAfterProblem(index)
    }

    /** تو حالت «پخش همه»، اگه آهنگی مشکل داشت، بعد از یه مکث کوتاه می‌ره سراغ بعدی. */
    private fun advanceAfterProblem(index: Int) {
        if (!playAll || index + 1 >= queueUrls.size) return
        val token = playToken
        handler.postDelayed({
            if (token == playToken && isActive) playIndex(index + 1)
        }, 1200)
    }

    // ───────────────────────── پخش ─────────────────────────

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val player = ExoPlayer.Builder(applicationContext).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) prefetchNext()
                if (state == Player.STATE_ENDED) onTrackEnded()
                publishState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingNow = isPlaying
                publishState()
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError()
            }
        })
        exo = player
        currentPlayer = player
        return player
    }

    private fun startPlaybackUri(uri: Uri) {
        val player = ensurePlayer()
        isPreparing = false
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = true
        player.prepare()
        publishState()
    }

    private fun onTrackEnded() {
        isPlayingNow = false
        val next = currentIndex + 1
        if (playAll && next < queueUrls.size) {
            val token = playToken
            handler.post {
                if (token == playToken && isActive) playIndex(next)
            }
        }
    }

    private fun onPlaybackError() {
        isPlayingNow = false
        isPreparing = false
        val message = "پخش این آهنگ ممکن نیست"
        errorText = message
        // فایل احتمالاً خراب دانلود شده؛ پاکش می‌کنیم تا دفعه‌ی بعد دوباره دانلود بشه
        queueUrls.getOrNull(currentIndex)?.let {
            if (isHttp(it)) {
                try {
                    cacheFileFor(it).delete()
                } catch (e: Exception) {
                    // بی‌اهمیت
                }
            }
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        publishState()
        advanceAfterProblem(currentIndex)
    }

    private fun togglePlayPause() {
        if (isPreparing) return
        val player = exo
        if (player == null || player.mediaItemCount == 0 || errorText != null) {
            playIndex(currentIndex)
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    private fun goNext() {
        if (currentIndex + 1 < queueUrls.size) playIndex(currentIndex + 1)
    }

    private fun goPrevious() {
        val position = try { exo?.currentPosition ?: 0L } catch (e: Exception) { 0L }
        if (position > 3000L || currentIndex == 0) {
            exo?.seekTo(0)
        } else {
            playIndex(currentIndex - 1)
        }
    }

    private fun stopAll() {
        playToken++
        tasks.values.forEach { it.cancelled.set(true) }
        tasks.clear()
        try {
            exo?.stop()
            exo?.release()
        } catch (e: Exception) {
            // بی‌اهمیت
        }
        exo = null
        currentPlayer = null
        isPlayingNow = false
        isPreparing = false
        isActive = false
        starting = false
        queueTitles = emptyList()
        queueUrls = emptyList()
        currentIndex = 0
        errorText = null
        try {
            cacheRoot.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // بی‌اهمیت
        }
        stateListener?.invoke()
        stopForeground(true)
        stopSelf()
    }

    // ───────────────────────── نوتیفیکیشن و وضعیت ─────────────────────────

    private fun toFa(n: Int): String =
        n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")

    private fun publishState() {
        updateMediaSession()
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // بی‌اهمیت
        }
        stateListener?.invoke()
    }

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val title = queueTitles.getOrNull(currentIndex) ?: "موزیک"
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .build()
        )
        val state = when {
            isPreparing -> PlaybackStateCompat.STATE_BUFFERING
            isPlayingNow -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val position = try { exo?.currentPosition ?: 0L } catch (e: Exception) { 0L }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, if (isPlayingNow) 1f else 0f)
                .build()
        )
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, MusicPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val title = queueTitles.getOrNull(currentIndex) ?: "موزیک"
        val status = when {
            isPreparing -> "در حال آماده‌سازی فایل…"
            isPlayingNow -> "در حال پخش"
            else -> "متوقف‌شده"
        }
        val counter = if (queueTitles.size > 1) {
            " — آهنگ " + toFa(currentIndex + 1) + " از " + toFa(queueTitles.size)
        } else ""
        val playPauseIcon = if (isPlayingNow) R.drawable.ic_pause else R.drawable.ic_play
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MusicPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(status + counter)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_previous, "قبلی", servicePendingIntent(11, ACTION_PREV))
            .addAction(playPauseIcon, if (isPlayingNow) "مکث" else "پخش", servicePendingIntent(12, ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "بعدی", servicePendingIntent(13, ACTION_NEXT))
            .addAction(R.drawable.ic_close, "توقف", servicePendingIntent(14, ACTION_STOP))
            .setOngoing(isPlayingNow || isPreparing)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val sessionToken = mediaSession?.sessionToken
        if (sessionToken != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "موزیک", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onDestroy() {
        playToken++
        tasks.values.forEach { it.cancelled.set(true) }
        tasks.clear()
        try {
            exo?.release()
        } catch (e: Exception) {
            // بی‌اهمیت
        }
        exo = null
        currentPlayer = null
        isActive = false
        isPlayingNow = false
        isPreparing = false
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
