package com.kafinet.asannet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * پخش رادیوی اینترنتی به‌صورت واقعی و در پس‌زمینه (سرویس فورگراند)، با ExoPlayer —
 * چون MediaPlayer خودِ اندروید برای استریم‌های زنده‌ی Shoutcast/Icecast قابل‌اعتماد
 * نیست (قطعی‌های بی‌صدا و بدون خطا). ExoPlayer دقیقاً برای همین کار ساخته شده.
 */
class RadioPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.kafinet.asannet.radio.PLAY"
        const val ACTION_TOGGLE = "com.kafinet.asannet.radio.TOGGLE"
        const val ACTION_STOP = "com.kafinet.asannet.radio.STOP"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DOWNLOADABLE = "extra_downloadable"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIFICATION_ID = 501

        @Volatile var isPlayingNow = false
            private set
        @Volatile var currentTitle = ""
            private set
        @Volatile private var trackCompleted = false

        @JvmStatic
        var currentPlayer: ExoPlayer? = null
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var streamUrl = ""
    private var pausedPositionMs = 0L
    private var retryCount = 0
    private var isDownloadableSource = false
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var prepareTimeoutRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "KafinetRadio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeOrStart()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.cat_radio)
                isDownloadableSource = intent.getBooleanExtra(EXTRA_DOWNLOADABLE, false)
                if (!url.isNullOrBlank()) startStream(url, title)
            }
            ACTION_TOGGLE -> if (isPlayingNow) pausePlayback() else resumeOrStart()
            ACTION_STOP -> stopPlayback()
        }
        return START_STICKY
    }

    private fun startStream(url: String, title: String, startPositionMs: Long = 0L, isRetry: Boolean = false) {
        streamUrl = url
        currentTitle = title
        trackCompleted = false
        if (!isRetry) retryCount = 0

        // برای فایل‌های صوتی معمولی (نه رادیوی زنده)، اول کامل دانلودش کن و از روی
        // خود گوشی پخش کن — چون بعضی سرورها (مثل Supabase) پخش تکه‌تکه‌ی مستقیم رو
        // درست جواب نمی‌دن و باعث قطعی پخش می‌شن
        if (isDownloadableSource && (url.startsWith("http://") || url.startsWith("https://"))) {
            downloadThenPlay(url, title, startPositionMs)
            return
        }

        playFromSource(url, title, startPositionMs, isRetry)
    }

    private fun downloadThenPlay(url: String, title: String, seekTarget: Long) {
        releasePlayerOnly()
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
        requestAudioFocus()

        val cacheFile = File(cacheDir, "radio_dl_" + url.hashCode() + ".dat")
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            playFromSource(cacheFile.absolutePath, title, seekTarget, isRetry = false)
            return
        }

        android.widget.Toast.makeText(applicationContext, "در حال آماده‌سازی فایل…", android.widget.Toast.LENGTH_SHORT).show()

        thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()
                connection.inputStream.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                retryHandler.post {
                    if (streamUrl == url) playFromSource(cacheFile.absolutePath, title, seekTarget, isRetry = false)
                }
            } catch (e: Exception) {
                cacheFile.delete()
                retryHandler.post {
                    if (streamUrl != url) return@post
                    if (retryCount < 2) {
                        retryCount++
                        downloadThenPlay(url, title, seekTarget)
                    } else {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "دانلود فایل ناموفق بود: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun playFromSource(url: String, title: String, startPositionMs: Long = 0L, isRetry: Boolean = false) {
        currentTitle = title
        releasePlayerOnly()

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())

        // توجه: حتی اگه گرفتن AudioFocus هم به هر دلیلی ناموفق باشه، بازم پخش رو
        // شروع کن — نباید کل پخش بی‌صدا و بدون هیچ خطایی متوقف بشه
        requestAudioFocus()

        try {
            val player = ExoPlayer.Builder(applicationContext).build()
            exoPlayer = player
            currentPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            prepareTimeoutRunnable?.let { r -> retryHandler.removeCallbacks(r) }
                            retryCount = 0
                        }
                        Player.STATE_ENDED -> {
                            trackCompleted = true
                            isPlayingNow = false
                            updateNotification()
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayingNow = isPlaying
                    updateNotification()
                }

                override fun onPlayerError(error: PlaybackException) {
                    prepareTimeoutRunnable?.let { r -> retryHandler.removeCallbacks(r) }
                    val savedPosition = try { player.currentPosition } catch (e: Exception) { 0L }
                    if (retryCount < 2) {
                        retryCount++
                        releasePlayerOnly()
                        retryHandler.postDelayed({
                            playFromSource(url, title, savedPosition, isRetry = true)
                        }, 800)
                    } else {
                        releasePlayerOnly()
                        android.widget.Toast.makeText(
                            applicationContext,
                            "خطا در پخش، اتصال اینترنت رو چک کن (${error.errorCodeName})",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })

            player.setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0) player.seekTo(startPositionMs)
            player.playWhenReady = true
            player.prepare()

            acquireWifiLock()

            // اگه ظرف ۱۲ ثانیه نه پخش شروع بشه نه خطایی اعلام بشه (حالت «بی‌صدا گیر
            // کردن»)، دیگه کاربر رو تو بلاتکلیفی نذار و یه پیام نشون بده.
            val timeoutRunnable = Runnable {
                if (!isPlayingNow) {
                    releasePlayerOnly()
                    android.widget.Toast.makeText(
                        applicationContext,
                        "اتصال به رادیو برقرار نشد، دوباره امتحان کن",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            prepareTimeoutRunnable = timeoutRunnable
            retryHandler.postDelayed(timeoutRunnable, 12000)
        } catch (e: Exception) {
            if (retryCount < 2) {
                retryCount++
                retryHandler.postDelayed({
                    playFromSource(url, title, startPositionMs, isRetry = true)
                }, 800)
            } else {
                android.widget.Toast.makeText(
                    applicationContext,
                    "خطا در شروع پخش: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                stopPlayback()
            }
        }
    }

    private fun resumeOrStart() {
        if (streamUrl.isBlank()) return
        startStream(streamUrl, currentTitle, pausedPositionMs)
    }

    private fun pausePlayback() {
        pausedPositionMs = try { exoPlayer?.currentPosition ?: 0L } catch (e: Exception) { 0L }
        releasePlayerOnly()
        updateNotification()
    }

    private fun stopPlayback() {
        releasePlayerOnly()
        releaseWifiLock()
        stopForeground(true)
        stopSelf()
    }

    private fun releasePlayerOnly() {
        exoPlayer?.apply {
            try { stop() } catch (e: Exception) { /* بی‌اهمیت */ }
            release()
        }
        exoPlayer = null
        currentPlayer = null
        isPlayingNow = false
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) pausePlayback()
                }
                .build()
            audioManager?.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            true
        }
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kafinet_radio_lock")
        wifiLock?.acquire()
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val playPauseIcon = if (isPlayingNow) R.drawable.ic_pause else R.drawable.ic_play
        val toggleIntent = PendingIntent.getService(
            this, 0, Intent(this, RadioPlayerService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RadioPlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, RadioPlayerActivity::class.java).apply {
                putExtra(RadioPlayerActivity.EXTRA_URL, streamUrl)
                putExtra(RadioPlayerActivity.EXTRA_TITLE, currentTitle)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = getString(if (isPlayingNow) R.string.radio_playing else R.string.radio_paused)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(currentTitle)
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .addAction(playPauseIcon, statusText, toggleIntent)
            .addAction(R.drawable.ic_close, getString(R.string.btn_stop), stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlayingNow)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, getString(R.string.cat_radio), NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        releasePlayerOnly()
        releaseWifiLock()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
