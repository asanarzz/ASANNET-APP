package com.kafinet.asannet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupabaseClient {

    /**
     * کاربر جدید را در جدول users در Supabase ثبت می‌کند.
     * اگر کد ملی از قبل ثبت شده باشد، درخواست نادیده گرفته می‌شود (نه خطا) — یعنی کاربر
     * از قبل جزو ثبت‌نامی‌هاست و باز هم اجازه‌ی ورود به اپ دارد.
     * در صورت موفقیت true برمی‌گرداند.
     */
    suspend fun registerUser(
        context: Context,
        firstName: String,
        lastName: String,
        nationalCode: String,
        phoneNumber: String,
        birthDate: String,
        passwordHash: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = context.getString(R.string.supabase_url).trimEnd('/')
        val anonKey = context.getString(R.string.supabase_anon_key)

        if (baseUrl.isBlank() || anonKey.isBlank()) {
            throw IllegalStateException(context.getString(R.string.error_backend_not_configured))
        }

        val url = URL("$baseUrl/rest/v1/users?on_conflict=national_code")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("apikey", anonKey)
        connection.setRequestProperty("Authorization", "Bearer $anonKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal")

        val body = JSONObject().apply {
            put("first_name", firstName)
            put("last_name", lastName)
            put("national_code", nationalCode)
            put("phone_number", phoneNumber)
            put("birth_date", birthDate)
            put("password_hash", passwordHash)
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        connection.disconnect()
        responseCode in 200..299
    }
}
