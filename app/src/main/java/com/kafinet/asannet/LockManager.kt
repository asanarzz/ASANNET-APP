package com.kafinet.asannet

import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * قفل توکنی: آیتم‌هایی که در content.json علامت locked دارند، لینک واقعی‌شان داخل
 * content.json نیست و فقط از سرور (Supabase) و فقط برای کاربری که توکن معتبر دارد
 * (متصل به همان کد ملی و همان گوشی) گرفته می‌شود.
 *
 * استفاده: LockManager.withAccess(activity, item) { realItem -> ...بازکردن عادی... }
 * اگر آیتم قفل نباشد، بلافاصله همان آیتم به onGranted داده می‌شود.
 */
object LockManager {

    private class RpcResult(
        val ok: Boolean,
        val error: String?,
        /** true یعنی مشکل ارتباط با سرور (نه جواب «دسترسی نداری») */
        val network: Boolean,
        val payload: JSONObject?
    )

    /** شناسه‌ی این گوشی؛ با حذف و نصب دوباره‌ی برنامه عوض نمی‌شود (ANDROID_ID). */
    fun deviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!androidId.isNullOrBlank()) return androidId
        val prefs = context.getSharedPreferences("kafinet_lock", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    fun withAccess(activity: AppCompatActivity, item: ContentItem, onGranted: (ContentItem) -> Unit) {
        if (!item.locked) {
            onGranted(item)
            return
        }
        val nationalCode = SessionManager.getNationalCode(activity)
        if (nationalCode.isNullOrBlank()) {
            Toast.makeText(activity, R.string.lock_err_login_required, Toast.LENGTH_LONG).show()
            return
        }
        activity.lifecycleScope.launch {
            val body = JSONObject()
                .put("p_item_id", item.id)
                .put("p_national_code", nationalCode)
                .put("p_device_id", deviceId(activity))
            val result = rpc(activity, "lock_get", body)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when {
                result.ok -> onGranted(applyPayload(item, result.payload))
                result.network || result.error == "item_missing" || result.error == "bad_request" ->
                    Toast.makeText(activity, errorText(activity, result.error), Toast.LENGTH_LONG).show()
                else -> showTokenDialog(activity, item, nationalCode, result.error, onGranted)
            }
        }
    }

    private fun showTokenDialog(
        activity: AppCompatActivity,
        item: ContentItem,
        nationalCode: String,
        reason: String?,
        onGranted: (ContentItem) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val message = TextView(activity).apply {
            text = activity.getString(
                when (reason) {
                    "expired" -> R.string.lock_dialog_message_expired
                    "revoked" -> R.string.lock_dialog_message_revoked
                    else -> R.string.lock_dialog_message_default
                },
                if (item.lockGroup != null && !item.section.isNullOrBlank())
                    activity.getString(R.string.lock_folder_name, item.section)
                else item.title
            )
            textSize = 15f
        }
        val input = EditText(activity).apply {
            hint = "XXXX-XXXX-XXXX"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            // فونت ساده‌ی سیستم تا ارقام توکن انگلیسی دیده شوند (فونت برنامه ارقام را فارسی می‌کند)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 18f
            filters = arrayOf(InputFilter.LengthFilter(24))
        }
        container.addView(message)
        container.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.lock_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.lock_confirm, null)
            .setNegativeButton(R.string.lock_cancel, null)
            .create()
        dialog.show()

        // listener بعد از show: دکمه‌ی تأیید تا جواب سرور نیاید پنجره را نمی‌بندد
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val token = input.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(activity, R.string.lock_err_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            activity.lifecycleScope.launch {
                val body = JSONObject()
                    .put("p_item_id", item.id)
                    .put("p_token", token)
                    .put("p_national_code", nationalCode)
                    .put("p_device_id", deviceId(activity))
                val result = rpc(activity, "lock_redeem", body)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (result.ok) {
                    dialog.dismiss()
                    onGranted(applyPayload(item, result.payload))
                } else {
                    button.isEnabled = true
                    Toast.makeText(activity, errorText(activity, result.error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun rpc(context: Context, function: String, body: JSONObject): RpcResult =
        withContext(Dispatchers.IO) {
            try {
                val conn = AdsClient.openConn(context, "/rest/v1/rpc/$function", "POST")
                AdsClient.writeJson(conn, body)
                val code = conn.responseCode
                val text = AdsClient.readBody(conn)
                conn.disconnect()
                if (code == 404) return@withContext RpcResult(false, "not_installed", true, null)
                if (code !in 200..299) return@withContext RpcResult(false, "server", true, null)
                val obj = JSONObject(text)
                if (obj.optBoolean("ok", false)) {
                    RpcResult(true, null, false, obj.optJSONObject("payload"))
                } else {
                    RpcResult(false, obj.optString("error", "unknown"), false, null)
                }
            } catch (e: Exception) {
                RpcResult(false, "network", true, null)
            }
        }

    /** لینک/عکس‌های واقعی (از سرور) را جای فیلدهای خالیِ آیتم می‌گذارد و قفل را برمی‌دارد. */
    private fun applyPayload(item: ContentItem, payload: JSONObject?): ContentItem {
        if (payload == null) return item.copy(locked = false)
        val url = if (payload.isNull("url")) "" else payload.optString("url", "").trim()

        val images = mutableListOf<String>()
        val imagesArray = payload.optJSONArray("images")
        if (imagesArray != null) {
            for (i in 0 until imagesArray.length()) {
                val u = imagesArray.optString(i, "").trim()
                if (u.isNotBlank()) images.add(u)
            }
        }

        val links = mutableListOf<DownloadLink>()
        val linksArray = payload.optJSONArray("links")
        if (linksArray != null) {
            for (i in 0 until linksArray.length()) {
                val obj = linksArray.optJSONObject(i) ?: continue
                val linkUrl = obj.optString("url", "").trim()
                if (linkUrl.isBlank()) continue
                links.add(DownloadLink(obj.optString("label", "").trim(), linkUrl))
            }
        }

        return item.copy(
            url = if (url.isNotBlank()) url else item.url,
            // عکس‌های عمومی (مثلاً کاور) اول می‌آیند، بعد عکس‌های مخفیِ گرفته‌شده از سرور
            images = if (images.isNotEmpty()) item.images + images else item.images,
            links = if (links.isNotEmpty()) links else item.links,
            locked = false
        )
    }

    private fun errorText(context: Context, error: String?): String = context.getString(
        when (error) {
            "invalid" -> R.string.lock_err_invalid
            "used" -> R.string.lock_err_used
            "wrong_item" -> R.string.lock_err_wrong_item
            "expired" -> R.string.lock_err_expired
            "revoked" -> R.string.lock_err_revoked
            "item_missing" -> R.string.lock_err_item_missing
            "not_installed" -> R.string.lock_err_not_installed
            "network" -> R.string.lock_err_network
            else -> R.string.lock_err_server
        }
    )
}
