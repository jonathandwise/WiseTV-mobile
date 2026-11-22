package com.wisetv.mobile.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wisetv.mobile.App
import java.util.concurrent.TimeUnit

/** Periodically refreshes playlists and the TV guide, and checks for app updates. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        App.graph.sync.refreshAll(force = false)
        App.graph.updater.checkAndDownload()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, hours: Int) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
