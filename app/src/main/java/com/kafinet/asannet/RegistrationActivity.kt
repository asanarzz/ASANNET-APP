package com.kafinet.asannet

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.kafinet.asannet.databinding.ActivityRegistrationBinding
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class RegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegistrationBinding
    private var isLoginMode = false
    private var selectedPhotoUri: Uri? = null

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPhotoUri = uri
            Glide.with(this).load(uri).into(binding.imgProfilePreview)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // اگر این گوشی قبلاً ثبت‌نام کرده، مستقیم برو به صفحه‌ی اصلی
        if (SessionManager.isRegistered(this)) {
            goToMain()
            return
        }

        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            if (isLoginMode) attemptLogin() else attemptRegister()
        }
        binding.txtToggleMode.setOnClickListener { toggleMode() }
        binding.txtForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
        binding.btnChoosePhoto.setOnClickListener { pickPhotoLauncher.launch("image/*") }
    }

    private fun toggleMode() {
        isLoginMode = !isLoginMode
        hideError()
        if (isLoginMode) {
            binding.extraFieldsGroup.visibility = android.view.View.GONE
            binding.extraFieldsGroup2.visibility = android.view.View.GONE
            binding.extraFieldsGroup3.visibility = android.view.View.GONE
            binding.txtForgotPassword.visibility = android.view.View.VISIBLE
            binding.btnRegister.text = getString(R.string.btn_login)
            binding.txtToggleMode.text = getString(R.string.toggle_to_register)
        } else {
            binding.extraFieldsGroup.visibility = android.view.View.VISIBLE
            binding.extraFieldsGroup2.visibility = android.view.View.VISIBLE
            binding.extraFieldsGroup3.visibility = android.view.View.VISIBLE
            binding.txtForgotPassword.visibility = android.view.View.GONE
            binding.btnRegister.text = getString(R.string.btn_register)
            binding.txtToggleMode.text = getString(R.string.toggle_to_login)
        }
    }

    private fun attemptLogin() {
        val nationalCode = binding.inNationalCode.text.toString().trim()
        val password = binding.inPassword.text.toString()

        if (nationalCode.isBlank() || password.isBlank()) {
            showError(getString(R.string.err_required_fields))
            return
        }
        if (!NationalCodeValidator.isValid(nationalCode)) {
            showError(getString(R.string.err_invalid_national_code))
            return
        }

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            try {
                val success = SupabaseClient.loginUser(this@RegistrationActivity, nationalCode, password)
                if (success) {
                    SessionManager.setRegistered(this@RegistrationActivity, nationalCode)
                    val name = SupabaseClient.fetchUserName(this@RegistrationActivity, nationalCode)
                    if (name != null) {
                        SessionManager.saveUserName(this@RegistrationActivity, name.first, name.second)
                    }
                    goToMain()
                } else {
                    showError(getString(R.string.err_invalid_login))
                }
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.err_network))
            } finally {
                setLoading(false)
            }
        }
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
        if (selectedPhotoUri == null) {
            showError(getString(R.string.err_photo_required))
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
                val photoBytes = compressPhoto(selectedPhotoUri!!)
                if (photoBytes == null) {
                    showError(getString(R.string.err_network))
                    return@launch
                }
                val photoUrl = SupabaseClient.uploadProfilePhoto(
                    this@RegistrationActivity, nationalCode, "image/jpeg", photoBytes
                )
                if (photoUrl == null) {
                    showError(getString(R.string.err_network))
                    return@launch
                }
                val passwordHash = PasswordHasher.hash(password)
                val success = SupabaseClient.registerUser(
                    this@RegistrationActivity, firstName, lastName, nationalCode, phone, birthDate, passwordHash, photoUrl
                )
                if (success) {
                    SessionManager.setRegistered(this@RegistrationActivity, nationalCode)
                    SessionManager.saveUserName(this@RegistrationActivity, firstName, lastName)
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

    /** عکس انتخاب‌شده را برای آپلود سریع‌تر، کوچک و فشرده می‌کند. */
    private fun compressPhoto(uri: Uri): ByteArray? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val maxDimension = 800
            val ratio = minOf(
                maxDimension.toFloat() / original.width,
                maxDimension.toFloat() / original.height,
                1f
            )
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true
                )
            } else {
                original
            }

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
            output.toByteArray()
        } catch (e: Exception) {
            null
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
