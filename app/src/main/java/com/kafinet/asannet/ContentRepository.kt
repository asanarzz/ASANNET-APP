package com.kafinet.asannet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * نتیجه‌ی بارگذاری محتوا: لیست آیتم‌ها به همراه اینکه آیا از سرور آمده یا از نسخه‌ی محلی.
 */
data class ContentResult(val items: List<ContentItem>, val fromRemote: Boolean, val error: Boolean)

object ContentRepository {

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 6000

    suspend fun load(context: Context): ContentResult = withContext(Dispatchers.IO) {
        val remoteUrl = context.getString(R.string.remote_content_url)

        if (remoteUrl.isNotBlank()) {
            val remoteJson = tryFetchRemote(remoteUrl)
            if (remoteJson != null) {
                return@withContext try {
                    ContentResult(ContentItem.listFromJson(remoteJson), fromRemote = true, error = false)
                } catch (e: Exception) {
                    ContentResult(loadLocal(context), fromRemote = false, error = true)
                }
            }
            // اتصال به سرور برقرار نشد -> استفاده از نسخه‌ی محلی به عنوان پشتیبان
            return@withContext ContentResult(loadLocal(context), fromRemote = false, error = true)
        }

        ContentResult(loadLocal(context), fromRemote = false, error = false)
    }

    private fun tryFetchRemote(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val text = reader.readText()
                reader.close()
                text
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadLocal(context: Context): List<ContentItem> {
        return try {
            val inputStream = context.assets.open("content.json")
            val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ContentItem.listFromJson(text)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
