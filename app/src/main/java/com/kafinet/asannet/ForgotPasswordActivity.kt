package com.kafinet.asannet

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityForgotPasswordBinding
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnReset.setOnClickListener { attemptReset() }
    }

    private fun attemptReset() {
        val nationalCode = binding.inNationalCode.text.toString().trim()
        val phone = binding.inPhone.text.toString().trim()
        val birthDate = binding.inBirthDate.text.toString().trim()
        val newPassword = binding.inNewPassword.text.toString()
        val newPasswordConfirm = binding.inNewPasswordConfirm.text.toString()

        if (nationalCode.isBlank() || phone.isBlank() || birthDate.isBlank() ||
            newPassword.isBlank() || newPasswordConfirm.isBlank()
        ) {
            showError(getString(R.string.err_required_fields))
            return
        }
        if (!NationalCodeValidator.isValid(nationalCode)) {
            showError(getString(R.string.err_invalid_national_code))
            return
        }
        if (!phone.matches(Regex("^09\\d{9}$"))) {
            showError(getString(R.string.err_invalid_phone))
            return
        }
        if (newPassword.length < 6) {
            showError(getString(R.string.err_password_short))
            return
        }
        if (newPassword != newPasswordConfirm) {
            showError(getString(R.string.err_password_mismatch))
            return
        }

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            try {
                val newHash = PasswordHasher.hash(newPassword)
                val success = SupabaseClient.resetPassword(
                    this@ForgotPasswordActivity, nationalCode, phone, birthDate, newHash
                )
                if (success) {
                    showError(getString(R.string.forgot_password_success), isSuccess = true)
                    binding.btnReset.postDelayed({ finish() }, 1600)
                } else {
                    showError(getString(R.string.forgot_password_no_match))
                }
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.err_network))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnReset.isEnabled = !loading
    }

    private fun showError(message: String, isSuccess: Boolean = false) {
        binding.txtError.text = message
        binding.txtError.setTextColor(
            if (isSuccess) resources.getColor(R.color.type_image, theme)
            else resources.getColor(R.color.danger, theme)
        )
        binding.txtError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = View.GONE
    }
}
