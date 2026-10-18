package com.kafinet.asannet

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityForgotPasswordBinding
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    // تاریخ تولد انتخاب‌شده از تقویم شمسی (سال، ماه، روز)؛ 0 یعنی هنوز انتخاب نشده
    private var pickedYear = 0
    private var pickedMonth = 0
    private var pickedDay = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnReset.setOnClickListener { attemptReset() }
        binding.inBirthDate.setOnClickListener { showBirthDatePicker() }
    }

    private fun attemptReset() {
        val nationalCode = binding.inNationalCode.text.toString().trim()
        val phone = binding.inPhone.text.toString().trim()
        val hasBirthDate = pickedYear != 0
        val newPassword = binding.inNewPassword.text.toString()
        val newPasswordConfirm = binding.inNewPasswordConfirm.text.toString()

        if (nationalCode.isBlank() || phone.isBlank() || !hasBirthDate ||
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
                // کاربرانی که قبلاً تاریخ را تایپی (با ارقام فارسی یا بدون صفر) ثبت کرده‌اند هم
                // کار کنند: چند شکل رایج تاریخ را به‌ترتیب امتحان می‌کنیم تا یکی بخورد.
                var success = false
                for (candidate in birthDateCandidates()) {
                    success = SupabaseClient.resetPassword(
                        this@ForgotPasswordActivity, nationalCode, phone, candidate, newHash
                    )
                    if (success) break
                }
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

    /** تقویم شمسی (سه چرخ سال/ماه/روز) برای انتخاب تاریخ تولد. */
    private fun showBirthDatePicker() {
        val y = if (pickedYear != 0) pickedYear else 1375
        val m = if (pickedMonth != 0) pickedMonth else 1
        val d = if (pickedDay != 0) pickedDay else 1
        PersianDatePickerDialog(this, y, m, d) { year, month, day ->
            pickedYear = year
            pickedMonth = month
            pickedDay = day
            binding.inBirthDate.setText(
                PersianDateUtils.toPersianDigits(PersianDateUtils.formatDate(year, month, day))
            )
        }.show()
    }

    /** شکل‌های ممکنِ ذخیره‌ی تاریخ تولد: اول فرمت جدید (1380/05/12)، بعد ارقام فارسی و بدون صفر. */
    private fun birthDateCandidates(): List<String> {
        val padded = PersianDateUtils.formatDate(pickedYear, pickedMonth, pickedDay)
        val plain = "$pickedYear/$pickedMonth/$pickedDay"
        return listOf(
            padded,
            PersianDateUtils.toPersianDigits(padded),
            plain,
            PersianDateUtils.toPersianDigits(plain)
        ).distinct()
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
