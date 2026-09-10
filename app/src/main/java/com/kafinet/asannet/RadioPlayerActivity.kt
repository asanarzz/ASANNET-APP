package com.kafinet.asannet

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.kafinet.asannet.databinding.ActivityRadioPlayerBinding
import kotlin.concurrent.thread

class RadioPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        // اگه true باشه یعنی این یه فایل صوتی معمولیه (نه رادیوی زنده‌ی پیوسته) و
        // پخش‌کننده اول کامل دانلودش می‌کنه، بعد از روی خود گوشی پخشش می‌کنه —
        // چون بعضی سرورها (مثل Supabase) پخش تکه‌تکه‌ی مستقیم رو درست جواب نمی‌دن
        const val EXTRA_DOWNLOADABLE = "extra_downloadable"
    }

    private lateinit var binding: ActivityRadioPlayerBinding
    private var streamUrl = ""
    private var stationTitle = ""
    private var downloadable = false


    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val progressTask = object : Runnable {
        override fun run() {
            val player = RadioPlayerService.currentPlayer
            if (player != null) {
                try {
                    val duration = player.duration
                    val position = player.currentPosition

                    if (duration > 0) {
                        binding.progressAudio.max = duration
                        binding.progressAudio.progress = position
                        binding.txtCurrentTime.text = formatTime(position)
                        binding.txtTotalTime.text = formatTime(duration)
                    }
                } catch (_: Exception) {
                }
            }
            updatePlayPauseIcon(RadioPlayerService.isPlayingNow)
            handler.postDelayed(this, 500)
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        stationTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.cat_radio) }
        downloadable = intent.getBooleanExtra(EXTRA_DOWNLOADABLE, false)
        binding.txtTitle.text = stationTitle

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }

        binding.progressAudio.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        binding.txtCurrentTime.text = formatTime(progress)
                    }
                }

                override fun onStartTrackingTouch(
                    seekBar: android.widget.SeekBar
                ) {}

                override fun onStopTrackingTouch(
                    seekBar: android.widget.SeekBar
                ) {
                    try {
                        RadioPlayerService.currentPlayer?.seekTo(seekBar.progress)
                    } catch (_: Exception) {}
                }
            }
        )

        // اگر همین ایستگاه از قبل در حال پخش است، فقط وضعیت را نشان بده؛ وگرنه پخش را شروع کن
        if (RadioPlayerService.isPlayingNow && RadioPlayerService.currentTitle == stationTitle) {
            updatePlayPauseIcon(true)
        } else {
            startPlayback()
        }

        handler.post(progressTask)

        loadCoverArt(streamUrl)
    }

    /**
     * سعی می‌کنه کاور آهنگ رو از تگ‌های خود فایل صوتی بخونه (برای فایل mp3 و مشابه).
     * اگه کاوری پیدا نشد، به‌جاش اسم آهنگ/خواننده رو (اگه تو تگ‌ها بود) نشون می‌ده؛
     * برای رادیوهای زنده معمولاً هیچ‌کدوم پیدا نمی‌شه و همون آیکون و عنوان پیش‌فرض می‌مونه.
     */
    private fun loadCoverArt(url: String) {
        if (url.isBlank()) return

        thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(url, HashMap<String, String>())

                val artBytes = retriever.embeddedPicture
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                retriever.release()

                runOnUiThread { applyCoverMetadata(artBytes, metaTitle, metaArtist) }
            } catch (e: Exception) {
                // فایل صوتی معمولی نیست (مثلاً استریم زنده‌ی رادیو) — همون ظاهر پیش‌فرض می‌مونه
            }
        }
    }

    private fun applyCoverMetadata(artBytes: ByteArray?, metaTitle: String?, metaArtist: String?) {
        val bitmap = artBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

        if (bitmap != null) {
            ImageViewCompat.setImageTintList(binding.imgCover, null)
            binding.imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.imgCover.setPadding(0, 0, 0, 0)
            binding.imgCover.setImageBitmap(bitmap)
        } else {
            if (!metaTitle.isNullOrBlank()) {
                binding.txtTitle.text = metaTitle
            }
            if (!metaArtist.isNullOrBlank()) {
                binding.txtArtist.text = metaArtist
                binding.txtArtist.visibility = View.VISIBLE
            }
        }
    }

    private fun startPlayback() {
        val serviceIntent = Intent(this, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_PLAY
            putExtra(RadioPlayerService.EXTRA_URL, streamUrl)
            putExtra(RadioPlayerService.EXTRA_TITLE, stationTitle)
            putExtra(RadioPlayerService.EXTRA_DOWNLOADABLE, downloadable)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updatePlayPauseIcon(true)
    }

    private fun togglePlayback() {
        val serviceIntent = Intent(this, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_TOGGLE
        }
        startService(serviceIntent)
        updatePlayPauseIcon(!RadioPlayerService.isPlayingNow)
    }

    private fun updatePlayPauseIcon(playing: Boolean) {
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        super.onDestroy()
    }
}
