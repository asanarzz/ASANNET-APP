package com.kafinet.asannet

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivitySupportBinding
import kotlinx.coroutines.launch

/**
 * چت پشتیبانی: کاربرِ واردشده با پشتیبانی (یک یا چند اپراتور) پیام رد و بدل
 * می‌کنه، همراه با امکان فرستادن عکس/ویدیو/فایل. برای این‌که حس زنده داشته باشه،
 * هر ۳ ثانیه (وقتی صفحه بازه) خودش پیام‌های تازه رو می‌گیره.
 */
class SupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportBinding
    private lateinit var adapter: SupportMessageAdapter
    private var nationalCode: String = ""
    private var userName: String = ""

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadMessages()
            refreshHandler.postDelayed(this, 3000)
        }
    }

    private val pickAttachment = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendAttachment(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nationalCode = SessionManager.getNationalCode(this).orEmpty()
        userName = SessionManager.getFullName(this).orEmpty()

        if (nationalCode.isBlank()) {
            Toast.makeText(this, R.string.support_login_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        adapter = SupportMessageAdapter(emptyList()) { message -> openAttachment(message) }
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAttach.setOnClickListener { pickAttachment.launch("*/*") }
        binding.btnSend.setOnClickListener { sendTextMessage() }

        loadMessages()
        markAsSeen()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.postDelayed(refreshRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
        markAsSeen()
    }

    private fun sendTextMessage() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.editMessage.setText("")
        lifecycleScope.launch {
            val ok = SupabaseClient.sendSupportMessage(this@SupportActivity, nationalCode, userName, text)
            if (!ok) Toast.makeText(this@SupportActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
            loadMessages()
        }
    }

    private fun sendAttachment(uri: Uri) {
        lifecycleScope.launch {
            try {
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = queryFileName(uri) ?: "file"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    Toast.makeText(this@SupportActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val attachmentType = when {
                    mimeType.startsWith("image/") -> "image"
                    mimeType.startsWith("video/") -> "video"
                    else -> "file"
                }
                Toast.makeText(this@SupportActivity, R.string.support_uploading, Toast.LENGTH_SHORT).show()
                val ok = SupabaseClient.sendSupportMessage(
                    this@SupportActivity, nationalCode, userName, "",
                    attachmentBytes = bytes,
                    attachmentFileName = fileName,
                    attachmentMimeType = mimeType,
                    attachmentType = attachmentType
                )
                if (!ok) Toast.makeText(this@SupportActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
                loadMessages()
            } catch (e: Exception) {
                Toast.makeText(this@SupportActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) name = it.getString(idx)
        }
        return name
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            val messages = SupabaseClient.fetchSupportMessages(this@SupportActivity, nationalCode)
            adapter.updateItems(messages)
            if (messages.isNotEmpty()) {
                binding.recyclerMessages.scrollToPosition(messages.size - 1)
            }
            markAsSeen(messages)
        }
    }

    private fun markAsSeen(messages: List<SupportMessage>? = null) {
        val list = messages ?: return
        val lastAdminTime = list.lastOrNull { it.sender == "admin" }?.createdAt ?: return
        getSharedPreferences("kafinet_support", MODE_PRIVATE)
            .edit()
            .putString("last_seen_reply_at", lastAdminTime)
            .apply()
    }

    private fun openAttachment(message: SupportMessage) {
        val url = message.attachmentUrl ?: return
        when (message.attachmentType) {
            "image" -> {
                val intent = android.content.Intent(this, ImageViewerActivity::class.java)
                intent.putExtra(ImageViewerActivity.EXTRA_URL, url)
                intent.putExtra(ImageViewerActivity.EXTRA_TITLE, message.attachmentName ?: "")
                startActivity(intent)
            }
            "video" -> DownloadHelper.downloadAndOpenExternally(this, url, message.attachmentName ?: "video", "video/*")
            else -> DownloadHelper.downloadAndOpenExternally(this, url, message.attachmentName ?: "file", "*/*")
        }
    }
}
