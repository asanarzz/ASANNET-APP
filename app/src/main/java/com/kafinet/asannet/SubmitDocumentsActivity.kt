package com.kafinet.asannet

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivitySubmitDocumentsBinding
import kotlinx.coroutines.launch
import java.util.UUID

class SubmitDocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubmitDocumentsBinding

    private val slots = mutableListOf<DocumentSlot>()
    private var activeSlot: DocumentSlot? = null

    private var selectedYear = 0
    private var selectedMonth = 0
    private var selectedDay = 0
    private var birthDateText: String = ""

    companion object {
        private const val MAX_DOCUMENTS = 10
    }

    /** یک ردیف مدرک (فایل انتخابی + توضیح مربوط به همان مدرک). */
    private inner class DocumentSlot(val rowView: View) {
        var uri: Uri? = null
        var name: String = ""
        var mime: String = "application/octet-stream"
        val noteEdit: EditText = rowView.findViewById(R.id.edit_doc_note)
        val fileNameText: TextView = rowView.findViewById(R.id.txt_file_name)
        val indexText: TextView = rowView.findViewById(R.id.txt_doc_index)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = activeSlot ?: return@registerForActivityResult
        if (uri != null) {
            slot.uri = uri
            slot.mime = contentResolver.getType(uri) ?: "application/octet-stream"
            slot.name = queryFileName(uri) ?: "file"
            slot.fileNameText.text = slot.name
            slot.fileNameText.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubmitDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddDocument.setOnClickListener { addDocumentSlot() }
        binding.btnPickBirthDate.setOnClickListener { showBirthDatePicker() }
        binding.btnSubmit.setOnClickListener { submit() }

        // یک ردیف مدرک به‌صورت پیش‌فرض نمایش داده می‌شود
        addDocumentSlot()
    }

    private fun addDocumentSlot() {
        if (slots.size >= MAX_DOCUMENTS) {
            Toast.makeText(this, R.string.docs_max_files, Toast.LENGTH_SHORT).show()
            return
        }
        val rowView = LayoutInflater.from(this)
            .inflate(R.layout.item_document_upload, binding.documentsContainer, false)
        val slot = DocumentSlot(rowView)
        slots.add(slot)
        updateIndexes()

        rowView.findViewById<View>(R.id.btn_pick_file).setOnClickListener {
            activeSlot = slot
            pickFile.launch("*/*")
        }
        rowView.findViewById<View>(R.id.btn_remove).setOnClickListener {
            binding.documentsContainer.removeView(rowView)
            slots.remove(slot)
            updateIndexes()
        }

        binding.documentsContainer.addView(rowView)
    }

    private fun updateIndexes() {
        slots.forEachIndexed { index, slot ->
            slot.indexText.text = PersianDateUtils.toPersianDigits((index + 1).toString())
        }
    }

    private fun showBirthDatePicker() {
        val baseYear = if (selectedYear != 0) selectedYear else 1375
        val baseMonth = if (selectedMonth != 0) selectedMonth else 1
        val baseDay = if (selectedDay != 0) selectedDay else 1

        PersianDatePickerDialog(this, baseYear, baseMonth, baseDay) { year, month, day ->
            selectedYear = year
            selectedMonth = month
            selectedDay = day
            birthDateText = PersianDateUtils.formatDate(year, month, day)
            binding.txtBirthDate.text = PersianDateUtils.toPersianDigits(birthDateText)
            binding.txtBirthDate.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        }.show()
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
        val applicantNationalCode = binding.editApplicantNationalCode.text?.toString()?.trim().orEmpty()
        val applicantPhoneNumber = binding.editApplicantPhone.text?.toString()?.trim().orEmpty()

        if (applicantNationalCode.isBlank() || applicantPhoneNumber.isBlank() || birthDateText.isBlank()) {
            Toast.makeText(this, R.string.err_applicant_fields_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (!NationalCodeValidator.isValid(applicantNationalCode)) {
            Toast.makeText(this, R.string.err_invalid_national_code, Toast.LENGTH_SHORT).show()
            return
        }
        if (!applicantPhoneNumber.matches(Regex("^09\\d{9}$"))) {
            Toast.makeText(this, R.string.err_invalid_phone, Toast.LENGTH_SHORT).show()
            return
        }

        val filledSlots = slots.filter { it.uri != null }
        if (filledSlots.isEmpty()) {
            Toast.makeText(this, R.string.err_pick_at_least_one_document, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmit.isEnabled = false
        binding.btnAddDocument.isEnabled = false
        binding.btnSubmit.text = getString(R.string.docs_uploading)

        val batchId = UUID.randomUUID().toString()

        lifecycleScope.launch {
            var allOk = true
            try {
                for (slot in filledSlots) {
                    val uri = slot.uri ?: continue
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        allOk = false
                        continue
                    }
                    val note = slot.noteEdit.text?.toString()?.trim().orEmpty()
                    val ok = SupabaseClient.uploadDocument(
                        context = this@SubmitDocumentsActivity,
                        fileName = slot.name,
                        mimeType = slot.mime,
                        bytes = bytes,
                        note = note,
                        applicantNationalCode = applicantNationalCode,
                        applicantPhoneNumber = applicantPhoneNumber,
                        applicantBirthDate = birthDateText,
                        batchId = batchId
                    )
                    if (!ok) allOk = false
                }

                if (allOk) {
                    Toast.makeText(this@SubmitDocumentsActivity, R.string.docs_success, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@SubmitDocumentsActivity, R.string.docs_error, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SubmitDocumentsActivity, R.string.err_network, Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnSubmit.isEnabled = true
                binding.btnAddDocument.isEnabled = true
                binding.btnSubmit.text = getString(R.string.docs_submit)
            }
        }
    }
}
