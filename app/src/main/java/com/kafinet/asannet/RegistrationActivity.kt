package com.kafinet.asannet

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityRegistrationBinding
import kotlinx.coroutines.launch

class RegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegistrationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // اگر این گوشی قبلاً ثبت‌نام کرده، مستقیم برو به صفحه‌ی اصلی
        if (SessionManager.isRegistered(this)) {
            goToMain()
            return
        }

        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { attemptRegister() }
    }

    private fun attemptRegister() {
        val firstName = binding.inFirstName.text.toString().trim()
        val lastName = binding.inLastName.text.toString().trim()
        val nationalCode = binding.inNationalCode.text.toString().trim()
        val phone = binding.inPhone.text.toString().trim()
        val birthDate = binding.inBirthDate.text.toString().trim()
        val password = binding.inPassword.text.toString()
        val passwordConfirm = binding.inPasswordConfirm.text.toString()

        if (firstName.isBlank() || lastName.isBlank() || nationalCode.isBlank() ||
            phone.isBlank() || birthDate.isBlank() || password.isBlank() || passwordConfirm.isBlank()
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
        if (password.length < 6) {
            showError(getString(R.string.err_password_short))
            return
        }
        if (password != passwordConfirm) {
            showError(getString(R.string.err_password_mismatch))
            return
        }

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            try {
                val passwordHash = PasswordHasher.hash(password)
                val success = SupabaseClient.registerUser(
                    this@RegistrationActivity, firstName, lastName, nationalCode, phone, birthDate, passwordHash
                )
                if (success) {
                    SessionManager.setRegistered(this@RegistrationActivity)
                    goToMain()
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
        binding.btnRegister.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.txtError.text = message
        binding.txtError.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        binding.txtError.visibility = android.view.View.GONE
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
