package com.example.oop.wearables.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.oop.wearables.WearablesConfig
import java.time.Instant
import java.util.concurrent.TimeUnit

class RetentionJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = WearableDatabase.get(applicationContext)
        val cutoffMs = Instant.now()
            .minusSeconds(WearablesConfig.retentionWindow.inWholeSeconds)
            .toEpochMilli()
        return runCatching {
            val deleted = database.vitalDao().pruneOlderThan(cutoffMs)
            Log.i(TAG, "Retention job pruned $deleted rows older than $cutoffMs")
            Result.success()
        }.getOrElse { throwable ->
            Log.w(TAG, "Retention job failed", throwable)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WearableRetentionJob"
        const val UNIQUE_NAME = "wearables_retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionJob>(1, TimeUnit.DAYS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
