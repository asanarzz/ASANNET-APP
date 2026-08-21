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
     * اگر کد ملی از قبل ثبت شده باشد (خطای ۴۰۹ به‌خاطر محدودیت unique)، همچنان موفق
     * در نظر گرفته می‌شود — یعنی کاربر از قبل جزو ثبت‌نامی‌هاست و اجازه‌ی ورود به اپ دارد.
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

        val url = URL("$baseUrl/rest/v1/users")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("apikey", anonKey)
        connection.setRequestProperty("Authorization", "Bearer $anonKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Prefer", "return=minimal")

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
        // 409 یعنی کد ملی تکراری است (محدودیت unique) — این یعنی کاربر از قبل ثبت‌نام کرده، نه خطا
        responseCode in 200..299 || responseCode == 409
    }

    /**
     * ورود با کد ملی و رمز عبور برای کاربری که قبلاً ثبت‌نام کرده (مثلاً بعد از نصب مجدد اپ).
     * از یک تابع امن سمت سرور (RPC) استفاده می‌کند که فقط true/false برمی‌گرداند —
     * هیچ اطلاعات دیگری از کاربران فاش نمی‌شود.
     */
    suspend fun loginUser(
        context: Context,
        nationalCode: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = context.getString(R.string.supabase_url).trimEnd('/')
        val anonKey = context.getString(R.string.supabase_anon_key)

        if (baseUrl.isBlank() || anonKey.isBlank()) {
            throw IllegalStateException(context.getString(R.string.error_backend_not_configured))
        }

        val url = URL("$baseUrl/rest/v1/rpc/verify_login")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("apikey", anonKey)
        connection.setRequestProperty("Authorization", "Bearer $anonKey")
        connection.setRequestProperty("Content-Type", "application/json")

        val body = JSONObject().apply {
            put("p_national_code", nationalCode)
            put("p_password", password)
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            return@withContext false
        }
        val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        connection.disconnect()
        responseText.trim() == "true"
    }

    /**
     * یک فایل (مدرک) را در Supabase Storage آپلود می‌کند و یک سطر در جدول
     * document_submissions ثبت می‌کند تا مدیر بتواند آن را ببیند.
     */
    suspend fun uploadDocument(
        context: Context,
        nationalCode: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        description: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = context.getString(R.string.supabase_url).trimEnd('/')
        val anonKey = context.getString(R.string.supabase_anon_key)

        if (baseUrl.isBlank() || anonKey.isBlank()) {
            throw IllegalStateException(context.getString(R.string.error_backend_not_configured))
        }

        val storagePath = "${nationalCode}/${System.currentTimeMillis()}_${fileName}"
        val uploadUrl = URL("$baseUrl/storage/v1/object/documents/$storagePath")
        val uploadConnection = uploadUrl.openConnection() as HttpURLConnection
        uploadConnection.requestMethod = "POST"
        uploadConnection.doOutput = true
        uploadConnection.connectTimeout = 15000
        uploadConnection.readTimeout = 15000
        uploadConnection.setRequestProperty("apikey", anonKey)
        uploadConnection.setRequestProperty("Authorization", "Bearer $anonKey")
        uploadConnection.setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })

        uploadConnection.outputStream.use { it.write(fileBytes) }
        val uploadResponseCode = uploadConnection.responseCode
        uploadConnection.disconnect()

        if (uploadResponseCode !in 200..299) {
            return@withContext false
        }

        val fileUrl = "$baseUrl/storage/v1/object/public/documents/$storagePath"

        val insertUrl = URL("$baseUrl/rest/v1/document_submissions")
        val insertConnection = insertUrl.openConnection() as HttpURLConnection
        insertConnection.requestMethod = "POST"
        insertConnection.doOutput = true
        insertConnection.connectTimeout = 8000
        insertConnection.readTimeout = 8000
        insertConnection.setRequestProperty("apikey", anonKey)
        insertConnection.setRequestProperty("Authorization", "Bearer $anonKey")
        insertConnection.setRequestProperty("Content-Type", "application/json")
        insertConnection.setRequestProperty("Prefer", "return=minimal")

        val body = JSONObject().apply {
            put("national_code", nationalCode)
            put("file_url", fileUrl)
            put("file_name", fileName)
            put("description", description)
        }
        insertConnection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val insertResponseCode = insertConnection.responseCode
        insertConnection.disconnect()
        insertResponseCode in 200..299
    }
}
