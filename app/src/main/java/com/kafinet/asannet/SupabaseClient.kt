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
     * یک فایل مدرک را در باکت «documents» آپلود می‌کند و سطر مربوطه را در جدول
     * document_submissions ثبت می‌کند. در صورت موفقیت کامل true برمی‌گرداند.
     * applicantNationalCode/applicantPhoneNumber/applicantBirthDate اطلاعات متقاضی‌اند که
     * برای هر مدرک تکرار می‌شوند و batchId مشترک برای گروه‌بندی مدارک یک ارسال
     * در پنل مدیریت استفاده می‌شود.
     */
    suspend fun uploadDocument(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        note: String,
        applicantNationalCode: String,
        applicantPhoneNumber: String,
        applicantBirthDate: String,
        batchId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = context.getString(R.string.supabase_url).trimEnd('/')
        val anonKey = context.getString(R.string.supabase_anon_key)

        if (baseUrl.isBlank() || anonKey.isBlank()) {
            throw IllegalStateException(context.getString(R.string.error_backend_not_configured))
        }

        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]+"), "-")
        val path = "docs/${System.currentTimeMillis()}-$safeName"

        val uploadUrl = URL("$baseUrl/storage/v1/object/documents/$path")
        val uploadConn = uploadUrl.openConnection() as HttpURLConnection
        uploadConn.requestMethod = "POST"
        uploadConn.doOutput = true
        uploadConn.connectTimeout = 15000
        uploadConn.readTimeout = 15000
        uploadConn.setRequestProperty("apikey", anonKey)
        uploadConn.setRequestProperty("Authorization", "Bearer $anonKey")
        uploadConn.setRequestProperty("Content-Type", mimeType)
        uploadConn.outputStream.use { it.write(bytes) }
        val uploadCode = uploadConn.responseCode
        uploadConn.disconnect()
        if (uploadCode !in 200..299) return@withContext false

        val publicUrl = "$baseUrl/storage/v1/object/public/documents/$path"
        val nationalCode = SessionManager.getNationalCode(context).orEmpty()

        val insertUrl = URL("$baseUrl/rest/v1/document_submissions")
        val insertConn = insertUrl.openConnection() as HttpURLConnection
        insertConn.requestMethod = "POST"
        insertConn.doOutput = true
        insertConn.connectTimeout = 8000
        insertConn.readTimeout = 8000
        insertConn.setRequestProperty("apikey", anonKey)
        insertConn.setRequestProperty("Authorization", "Bearer $anonKey")
        insertConn.setRequestProperty("Content-Type", "application/json")
        insertConn.setRequestProperty("Prefer", "return=minimal")

        val body = JSONObject().apply {
            put("national_code", nationalCode)
            put("file_url", publicUrl)
            put("file_name", fileName)
            put("description", note)
            put("applicant_national_code", applicantNationalCode)
            put("applicant_phone_number", applicantPhoneNumber)
            put("applicant_birth_date", applicantBirthDate)
            put("batch_id", batchId)
        }
        insertConn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val insertCode = insertConn.responseCode
        insertConn.disconnect()
        insertCode in 200..299
    }

    /**
     * ورود کاربری که قبلاً ثبت‌نام کرده (بعد از حذف و نصب مجدد برنامه، یا روی گوشی دیگر).
     * هش ذخیره‌شده‌ی رمز عبور را از طریق یک تابع امن (RPC) می‌گیرد و مقایسه‌ی نهایی
     * (با همان salt ذخیره‌شده) داخل خود اپ انجام می‌شود.
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

        val url = URL("$baseUrl/rest/v1/rpc/get_password_hash")
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
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) return@withContext false

        // پاسخ RPC برای مقدار متنی به‌صورت رشته‌ی JSON برمی‌گردد، مثلاً "salt:hash" یا null
        val storedHash = text.trim().removeSurrounding("\"")
        if (storedHash.isBlank() || storedHash == "null") return@withContext false

        PasswordHasher.verify(password, storedHash)
    }
}