package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafinet.asannet.databinding.ActivityChatBinding
import com.kafinet.asannet.databinding.ActivityChatsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/* ===================================================================
 *  صفحه‌ی چت بین دو کاربر (خریدار و فروشنده‌ی یک آگهی)
 * =================================================================== */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_TITLE = "chat_title"
    }

    private lateinit var binding: ActivityChatBinding
    private val adapter = MessagesAdapter()
    private val mutex = Mutex()
    private var chatId: String = ""
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        if (chatId.isBlank()) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.txtTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        binding.recyclerMessages.layoutManager = lm
        binding.recyclerMessages.adapter = adapter

        binding.btnSend.setOnClickListener { sendMessage() }
    }

    override fun onResume() {
        super.onResume()
        if (chatId.isBlank()) return
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                loadNew()
                delay(4000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun loadNew() {
        mutex.withLock {
            val result = ChatClient.messages(this@ChatActivity, chatId, adapter.lastId())
            if (!result.error && result.messages.isNotEmpty()) {
                adapter.add(result.messages)
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            }
            binding.txtEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private fun sendMessage() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            val ok = ChatClient.send(this@ChatActivity, chatId, text)
            if (ok) {
                binding.editMessage.setText("")
                loadNew()
            } else {
                Toast.makeText(
                    this@ChatActivity,
                    "پیام ارسال نشد. اینترنت را بررسی کنید و کمی بعد دوباره امتحان کنید",
                    Toast.LENGTH_SHORT
                ).show()
            }
            binding.btnSend.isEnabled = true
        }
    }
}

/* ===================================================================
 *  صندوق پیام‌ها: همه‌ی گفتگوهای من (به‌عنوان خریدار یا فروشنده)
 * =================================================================== */
class ChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatsBinding
    private lateinit var adapter: ChatListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = ChatListAdapter(emptyList()) { chat ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chat.chatId)
            intent.putExtra(ChatActivity.EXTRA_TITLE, chat.adTitle)
            startActivity(intent)
        }
        binding.recyclerChats.layoutManager = LinearLayoutManager(this)
        binding.recyclerChats.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val result = ChatClient.inbox(this@ChatsActivity)
            adapter.update(result.chats)
            binding.txtEmpty.visibility = if (result.chats.isEmpty()) View.VISIBLE else View.GONE
            if (result.error) {
                Toast.makeText(this@ChatsActivity, "خطا در دریافت پیام‌ها", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
