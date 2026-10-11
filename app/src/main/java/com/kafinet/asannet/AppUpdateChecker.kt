package com.kafinet.asannet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** true یعنی کاربر بدون به‌روزرسانی نمی‌تواند از برنامه استفاده کند. */
    val force: Boolean = true
)

/**
 * وضعیت آخرین نسخه‌ی منتشرشده رو از فایلی که خودِ بیلد گیت‌هاب بعد از هر بیلد موفق
 * می‌سازه (apk/version.json) می‌خونه، و اگه جدیدتر از نسخه‌ی نصب‌شده‌ی فعلی بود
 * برمی‌گردونه؛ در غیر این صورت null.
 */
object AppUpdateChecker {

    private const val VERSION_URL = "https://asanarzz.github.io/ASANNET-APP/apk/version.json"
    // فایل اختیاری برای خاموش/روشن‌کردن اجباری‌بودن: {"force": false} یا {"force": true}
    // اگر این فایل وجود نداشته باشد، به‌روزرسانی اجباری است.
    private const val FORCE_URL = "https://asanarzz.github.io/ASANNET-APP/apk/force.json"

    suspend fun checkForUpdate(currentVersionCode: Int): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.connect()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val remoteCode = obj.optInt("versionCode", -1)
            val remoteName = obj.optString("versionName", "")
            val apkUrl = obj.optString(
                "apkUrl",
                "https://asanarzz.github.io/ASANNET-APP/apk/latest.apk"
            )
            if (remoteCode > currentVersionCode) {
                AppUpdateInfo(remoteCode, remoteName, apkUrl, readForceFlag())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readForceFlag(): Boolean {
        return try {
            val connection = URL(FORCE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.connect()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optBoolean("force", true)
        } catch (e: Exception) {
            true
        }
    }
}
