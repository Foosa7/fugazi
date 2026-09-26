package com.fugazi.app.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fugazi.app.R
import java.time.LocalDate

/**
 * A reflection you asked for. It runs as its own job with an ongoing notification, so it
 * finishes even if you switch apps or leave the Reflect tab — Kimi can take a few minutes —
 * and it tells you when it's ready.
 */
class ReflectWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reflecting", NotificationManager.IMPORTANCE_LOW),
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Reading your day…")
            .setContentText("Kimi is thinking. This can take a few minutes.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTE_ID, n)
    }

    override suspend fun doWork(): Result {
        val day = inputData.getString(KEY_DAY)?.let(LocalDate::parse) ?: return Result.failure()
        runCatching { setForeground(getForegroundInfo()) }
        return Reflector.reflect(applicationContext, day).fold(
            onSuccess = { text ->
                Reflector.notifyReady(applicationContext, day, text)
                Result.success()
            },
            onFailure = { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "Something went wrong"))) },
        )
    }

    companion object {
        const val WORK = "fugazi_reflect"
        const val KEY_DAY = "day"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "reflecting"
        private const val NOTE_ID = 4242

        fun start(ctx: Context, day: LocalDate) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK,
                // A second tap while one is running doesn't start another.
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReflectWorker>()
                    .setInputData(workDataOf(KEY_DAY to day.toString()))
                    .addTag("day:$day")
                    .build(),
            )
        }
    }
}
