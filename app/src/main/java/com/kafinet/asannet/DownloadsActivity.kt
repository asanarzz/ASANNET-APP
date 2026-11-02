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
            refreshHandler.postDelayed(this, 1000)
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
                val status = AppDownloadManager.queryStatus(this, record.downloadId)
                val stillActive = status.state == DownloadState.RUNNING ||
                    status.state == DownloadState.PENDING ||
                    status.state == DownloadState.PAUSED ||
                    status.state == DownloadState.LISTED ||
                    status.state == DownloadState.FAILED
                if (stillActive) {
                    // برای دانلودهای نیمه‌کاره، فایل ناقص هم پاک می‌شه
                    AppDownloadManager.cancelDownload(this, record.downloadId)
                }
                AppDownloadManager.removeRecord(this, record.downloadId)
                loadDownloads()
            },
            onToggle = { record, status ->
                if (status.state == DownloadState.RUNNING) {
                    AppDownloadService.pause(this, record.downloadId)
                } else if (DownloadHelper.ensureStoragePermission(this)) {
                    // شروع (از لیست)، ادامه (بعد از توقف) یا تلاش دوباره (بعد از خطا)
                    AppDownloadService.start(this, record.downloadId)
                }
            }
        )
        binding.recyclerDownloads.layoutManager = LinearLayoutManager(this)
        binding.recyclerDownloads.adapter = adapter

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.postDelayed(refreshRunnable, 1000)
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
