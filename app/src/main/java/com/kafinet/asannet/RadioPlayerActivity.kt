package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kafinet.asannet.databinding.ActivityRadioPlayerBinding

class RadioPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityRadioPlayerBinding
    private var streamUrl = ""
    private var stationTitle = ""


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
        binding.txtTitle.text = stationTitle

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }

        // اگر همین ایستگاه از قبل در حال پخش است، فقط وضعیت را نشان بده؛ وگرنه پخش را شروع کن
        if (RadioPlayerService.isPlayingNow && RadioPlayerService.currentTitle == stationTitle) {
            updatePlayPauseIcon(true)
        } else {
            startPlayback()
        }

        handler.post(progressTask)
    }

    private fun startPlayback() {
        val serviceIntent = Intent(this, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_PLAY
            putExtra(RadioPlayerService.EXTRA_URL, streamUrl)
            putExtra(RadioPlayerService.EXTRA_TITLE, stationTitle)
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
