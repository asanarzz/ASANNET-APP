package com.kafinet.asannet

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class Mp3PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val status = TextView(this).apply {
            text = "در حال آماده‌سازی…"
            textSize = 18f
        }

        val play = Button(this).apply {
            text = "پخش"
            isEnabled = false
        }

        val seek = SeekBar(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(play)
            addView(seek)
        }

        setContentView(layout)

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            setDataSource(url)

            setOnPreparedListener {
                status.text = "آماده پخش"
                play.isEnabled = true
                seek.max = duration
                start()
                play.text = "مکث"
            }

            setOnCompletionListener {
                play.text = "پخش دوباره"
            }

            setOnErrorListener { _, _, _ ->
                status.text = "پخش فایل ممکن نیست"
                true
            }
        }

        play.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                    play.text = "پخش"
                } else {
                    it.start()
                    play.text = "مکث"
                }
            }
        }

        seek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                bar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) player?.seekTo(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        player?.prepareAsync()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
