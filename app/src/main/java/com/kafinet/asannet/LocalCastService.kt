package com.kafinet.asannet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * سرویس پیش‌زمینه برای ارسال فایل‌های گوشی به تلویزیون (فقط برای «مدیریت فایل ← ارسال به تلویزیون»).
 * مستقل از CastRelayService است که مخصوص پخش زنده‌ی شبکه‌هاست.
 */
class LocalCastService : Service() {

    companion object {
        const val ACTION_STOP = "com.kafinet.asannet.LOCAL_CAST_STOP"
        private const val CHANNEL_ID = "local_cast"
        private const val NOTIFICATION_ID = 7422
        private const val IDLE_LIMIT_MS = 60 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocalCastService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalCastService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            if (!LocalCastEngine.isRunning() || LocalCastEngine.idleMillis() > IDLE_LIMIT_MS) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread { LocalCastEngine.stopByUser() }.start()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5000)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ارسال فایل به تلویزیون", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocalCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, LocalCastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv_cast)
            .setContentTitle("ارسال فایل به تلویزیون")
            .setContentText("فایل از گوشی روی تلویزیون پخش می‌شود. گوشی را روی همین Wi-Fi نگه دارید.")
            .setContentIntent(openIntent)
            .addAction(0, "توقف", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asannet:localcast")
            lock.setReferenceCounted(false)
            lock.acquire(6 * 60 * 60 * 1000L)
            wakeLock = lock
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "asannet:localcast")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
        // فقط سرور فایل خودِ این قابلیت بسته می‌شود؛ به پخش زنده و تلویزیون کاری ندارد
        LocalCastEngine.stopServer()
        super.onDestroy()
    }
}
