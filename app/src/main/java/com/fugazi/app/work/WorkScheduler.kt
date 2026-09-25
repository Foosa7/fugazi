package com.fugazi.app.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val UNIQUE_NAME = "fugazi_daily_radar"

    /** Schedule the recurring radar pass. Idempotent; survives reboots via WorkManager. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyRadarWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Kick a one-off pass now (used right after setup so data starts flowing). */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DailyRadarWorker>().build(),
        )
    }
}
