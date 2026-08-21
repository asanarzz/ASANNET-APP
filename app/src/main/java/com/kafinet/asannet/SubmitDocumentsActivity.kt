package com.kafinet.asannet

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivitySubmitDocumentsBinding
import kotlinx.coroutines.launch

class SubmitDocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubmitDocumentsBinding
    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var selectedMime: String = "application/octet-stream"

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedMime = contentResolver.getType(uri) ?: "application/octet-stream"
            selectedName = queryFileName(uri) ?: "file"
            binding.txtFileName.text = selectedName
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubmitDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPickFile.setOnClickListener { pickFile.launch("*/*") }
        binding.btnSubmit.setOnClickListener { submit() }
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

    private fun submit() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, R.string.docs_pick_file_first, Toast.LENGTH_SHORT).show()
            return
        }
        val note = binding.editNote.text?.toString().orEmpty()

        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = getString(R.string.docs_uploading)

        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    Toast.makeText(this@SubmitDocumentsActivity, R.string.docs_error, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val ok = SupabaseClient.uploadDocument(this@SubmitDocumentsActivity, selectedName, selectedMime, bytes, note)
                if (ok) {
                    Toast.makeText(this@SubmitDocumentsActivity, R.string.docs_success, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@SubmitDocumentsActivity, R.string.docs_error, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SubmitDocumentsActivity, R.string.err_network, Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = getString(R.string.docs_submit)
            }
        }
    }
}
