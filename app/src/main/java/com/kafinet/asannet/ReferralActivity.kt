package com.kafinet.asannet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.kafinet.asannet.databinding.ActivityReferralBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** صفحه‌ی «معرفی برنامه به دوستان»: کد اختصاصی کاربر، تعداد دعوت‌های موفق و ارسال فایل برنامه همراه کد. */
class ReferralActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReferralBinding
    private var code: String? = null

    companion object {
        // لینک کوتاهِ دانلود که برای دوستان فرستاده می‌شود (آدرس اصلی پشت آن مخفی است).
        // اگر اسم دیگری برای لینک کوتاه گرفتید، فقط همین خط را عوض کنید.
        private const val DOWNLOAD_URL = "https://zaya.io/mnhjy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReferralBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopy.setOnClickListener { copyCode() }
        binding.btnShare.setOnClickListener { shareApkWithCode() }
        binding.btnShareLink.setOnClickListener { shareLinkWithCode() }

        loadInfo()
    }

    private fun loadInfo() {
        val nationalCode = SessionManager.getNationalCode(this)
        if (nationalCode.isNullOrBlank()) {
            finish()
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.txtStatus.visibility = View.GONE
        lifecycleScope.launch {
            val info = SupabaseClient.getReferralInfo(this@ReferralActivity, nationalCode)
            binding.progress.visibility = View.GONE
            if (info == null) {
                binding.txtStatus.text = "دریافت کد ممکن نشد. اینترنت را بررسی کنید و دوباره وارد این صفحه شوید."
                binding.txtStatus.visibility = View.VISIBLE
                return@launch
            }
            code = info.first
            // اعداد به‌صورت انگلیسی نمایش داده می‌شوند
            binding.txtCode.text = forceEnglishDigits(info.first)
            binding.txtCount.text = forceEnglishDigits(
                "تعداد دوستانی که با کد شما ثبت‌نام کرده‌اند: " + info.second.toString()
            )
            binding.btnShareLink.isEnabled = true
            binding.btnShare.isEnabled = true
            binding.btnCopy.isEnabled = true
        }
    }

    private fun copyCode() {
        val c = code ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("referral", c))
        Toast.makeText(this, "کد کپی شد", Toast.LENGTH_SHORT).show()
    }

    /** لینک دانلود برنامه را همراه کد معرف به‌صورت یک پیام متنی می‌فرستد (در همه‌ی پیام‌رسان‌ها کار می‌کند). */
    private fun shareLinkWithCode() {
        val c = code ?: return
        val message = "سلام! برنامه «آسان نت» را از این لینک دانلود و نصب کن:\n" +
            DOWNLOAD_URL + "\n\n" +
            "موقع ثبت‌نام، در کادر «کد معرف» این کد را بنویس:\n" +
            c + "\n\n" +
            "کافی‌نت آسان نت | @ASANNETT"
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_apk_chooser_title)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.share_apk_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** فایل نصب برنامه را همراه متن آماده (شامل کد معرف) به اشتراک می‌گذارد. */
    private fun shareApkWithCode() {
        val c = code ?: return
        lifecycleScope.launch {
            try {
                val destUri = withContext(Dispatchers.IO) {
                    val src = File(applicationInfo.sourceDir)
                    val destDir = File(cacheDir, "share")
                    destDir.mkdirs()
                    val dest = File(destDir, "AsanNet.apk")
                    src.copyTo(dest, overwrite = true)
                    FileProvider.getUriForFile(this@ReferralActivity, "$packageName.fileprovider", dest)
                }
                val message = "سلام! برنامه «آسان نت» را نصب کن.\n" +
                    "موقع ثبت‌نام، در کادر «کد معرف» این کد را بنویس:\n" +
                    c + "\n" +
                    "کافی‌نت آسان نت | @ASANNETT"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, destUri)
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_apk_chooser_title)))
            } catch (e: Exception) {
                Toast.makeText(this@ReferralActivity, R.string.share_apk_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
