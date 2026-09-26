package com.fugazi.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** One Drive sync, whenever there's a network. The 4-hourly check also syncs. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        DriveSync.sync(applicationContext)
        return Result.success()
    }

    companion object {
        /**
         * Sync shortly after you leave the app, so what you just wrote is in Drive within a
         * minute. Leaving again before then pushes the sync back instead of queueing another.
         */
        fun soon(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "fugazi_drive_sync",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInitialDelay(20, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
