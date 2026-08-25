package com.kafinet.asannet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * هر چند ساعت یک‌بار content.json را چک می‌کند؛ اگر آیتم جدیدی نسبت به آخرین بار
 * دیده شود، یک نوتیفیکیشن محلی نشان می‌دهد. کاملاً محلی و بدون نیاز به Firebase.
 */
class ContentCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = ContentRepository.load(applicationContext)
            if (!result.fromRemote) return Result.retry()

            val prefs = applicationContext.getSharedPreferences("kafinet_content_check", Context.MODE_PRIVATE)
            val knownIds = prefs.getStringSet("known_ids", null)
            val currentIds = result.items.map { it.id }.toSet()

            if (knownIds != null) {
                val newItems = result.items.filter { it.id !in knownIds }
                if (newItems.isNotEmpty()) {
                    NotificationHelper.createChannel(applicationContext)
                    val message = if (newItems.size == 1) {
                        newItems.first().title
                    } else {
                        applicationContext.getString(R.string.notif_multiple_new, newItems.size)
                    }
                    NotificationHelper.showNewContentNotification(
                        applicationContext,
                        applicationContext.getString(R.string.notif_new_content_title),
                        message
                    )
                }
            }

            prefs.edit().putStringSet("known_ids", currentIds).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "content_check_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ContentCheckWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
