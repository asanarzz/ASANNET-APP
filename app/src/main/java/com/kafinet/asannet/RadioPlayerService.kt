package com.kafinet.asannet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat

/**
 * پخش رادیوی اینترنتی به‌صورت واقعی و در پس‌زمینه (سرویس فورگراند) —
 * برخلاف پخش داخل WebView، با ترک کردن صفحه یا خاموش‌کردن صفحه‌ی گوشی قطع نمی‌شود.
 */
class RadioPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.kafinet.asannet.radio.PLAY"
        const val ACTION_TOGGLE = "com.kafinet.asannet.radio.TOGGLE"
        const val ACTION_STOP = "com.kafinet.asannet.radio.STOP"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIFICATION_ID = 501

        @Volatile var isPlaying = false
        @Volatile var currentTitle = ""
    }

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var streamUrl = ""

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
                if (!url.isNullOrBlank()) startStream(url, title)
            }
            ACTION_TOGGLE -> if (isPlaying) pausePlayback() else resumeOrStart()
            ACTION_STOP -> stopPlayback()
        }
        return START_STICKY
    }

    private fun startStream(url: String, title: String) {
        streamUrl = url
        currentTitle = title
        releaseMediaPlayerOnly()

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!requestAudioFocus()) return

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener {
                    it.start()
                    isPlaying = true
                    updateNotification()
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
            acquireWifiLock()
        } catch (e: Exception) {
            stopPlayback()
        }
    }

    private fun resumeOrStart() {
        if (mediaPlayer == null) {
            if (streamUrl.isNotBlank()) startStream(streamUrl, currentTitle)
            return
        }
        if (!requestAudioFocus()) return
        mediaPlayer?.start()
        isPlaying = true
        updateNotification()
    }

    private fun pausePlayback() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) { /* بی‌اهمیت */ }
        isPlaying = false
        updateNotification()
    }

    private fun stopPlayback() {
        releaseMediaPlayerOnly()
        releaseWifiLock()
        stopForeground(true)
        stopSelf()
    }

    private fun releaseMediaPlayerOnly() {
        mediaPlayer?.apply {
            try { stop() } catch (e: Exception) { /* بی‌اهمیت */ }
            release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
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

        val statusText = getString(if (isPlaying) R.string.radio_playing else R.string.radio_paused)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(currentTitle)
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .addAction(playPauseIcon, statusText, toggleIntent)
            .addAction(R.drawable.ic_close, getString(R.string.btn_stop), stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlaying)
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
        releaseMediaPlayerOnly()
        releaseWifiLock()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
