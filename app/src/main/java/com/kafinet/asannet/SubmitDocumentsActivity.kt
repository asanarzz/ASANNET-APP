package com.kafinet.asannet

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivitySubmitDocumentsBinding
import kotlinx.coroutines.launch

class SubmitDocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubmitDocumentsBinding
    private var selectedUri: Uri? = null
    private var selectedFileName: String = ""

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedFileName = queryFileName(uri)
            binding.txtChosenFile.text = selectedFileName
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubmitDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChooseFile.setOnClickListener { pickFileLauncher.launch("*/*") }
        binding.btnSend.setOnClickListener { attemptSend() }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    private fun attemptSend() {
        val uri = selectedUri
        if (uri == null) {
            showError(getString(R.string.err_choose_file_first))
            return
        }

        val nationalCode = SessionManager.getNationalCode(this)
        val description = binding.inDescription.text.toString().trim()

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    showError(getString(R.string.err_network))
                    return@launch
                }
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val success = SupabaseClient.uploadDocument(
                    this@SubmitDocumentsActivity, nationalCode, selectedFileName, mimeType, bytes, description
                )
                if (success) {
                    android.widget.Toast.makeText(
                        this@SubmitDocumentsActivity, R.string.document_sent_success, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    showError(getString(R.string.err_network))
                }
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.err_network))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnSend.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = android.view.View.GONE
    }
}
