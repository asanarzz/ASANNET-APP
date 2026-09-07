package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kafinet.asannet.databinding.ActivityM3uPlaylistBinding
import java.net.URL
import kotlin.concurrent.thread

class M3uPlaylistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityM3uPlaylistBinding
    private val channelNames = ArrayList<String>()
    private val channelUrls = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityM3uPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.txtTitle.text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "فهرست کانال‌ها" }

        val playlistUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (playlistUrl.isBlank()) {
            Toast.makeText(this, "آدرس M3U خالی است", Toast.LENGTH_LONG).show()
            return
        }

        thread {
            try {
                val text = URL(playlistUrl).readText()
                parsePlaylist(text)

                runOnUiThread {
                    binding.progress.visibility = View.GONE

                    if (channelNames.isEmpty()) {
                        Toast.makeText(
                            this,
                            "کانالی در فایل M3U پیدا نشد",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        binding.listChannels.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_list_item_1,
                            channelNames
                        )

                        binding.listChannels.setOnItemClickListener { _, _, position, _ ->
                            startActivity(
                                Intent(this, VideoPlayerActivity::class.java).apply {
                                    putExtra(
                                        VideoPlayerActivity.EXTRA_URL,
                                        channelUrls[position]
                                    )
                                    putExtra(
                                        VideoPlayerActivity.EXTRA_TITLE,
                                        channelNames[position]
                                    )
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    binding.progress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "خطا در خواندن فایل M3U",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun parsePlaylist(text: String) {
        var name = ""

        text.lineSequence().forEach { raw ->
            val line = raw.trim()

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(",").trim()
                    if (name.isBlank()) name = "کانال"
                }

                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    name.isNotBlank() -> {
                    channelNames.add(name)
                    channelUrls.add(line)
                    name = ""
                }
            }
        }
    }
}
