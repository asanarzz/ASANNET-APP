package com.kafinet.asannet

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityDownloadsBinding

/**
 * صفحه‌ی «دانلودها»: همه‌ی فایل‌هایی که از هر جای اپ (فیلم، سریال، عکس، فایل و...)
 * دانلود شدن رو یکجا نشون می‌ده، با وضعیت زنده (در حال دانلود / تکمیل شد / ناموفق).
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadDownloads()
            refreshHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = DownloadsAdapter(
            emptyList(),
            onClick = { record, status ->
                if (status.state == DownloadState.SUCCESSFUL || status.state == DownloadState.UNKNOWN) {
                    AppDownloadManager.openDownload(this, record.downloadId)
                }
            },
            onRemove = { record ->
                AppDownloadManager.removeRecord(this, record.downloadId)
                loadDownloads()
            }
        )
        binding.recyclerDownloads.layoutManager = LinearLayoutManager(this)
        binding.recyclerDownloads.adapter = adapter

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.postDelayed(refreshRunnable, 1500)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun loadDownloads() {
        val records = AppDownloadManager.getAllRecords(this)
        val withStatus = records.map { record ->
            record to AppDownloadManager.queryStatus(this, record.downloadId)
        }
        adapter.updateItems(withStatus)
        binding.layoutEmpty.visibility = if (withStatus.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
