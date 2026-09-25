package com.fugazi.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fugazi.app.data.AppDatabase
import com.fugazi.app.data.DailyUsage
import com.fugazi.app.data.SetupStore
import com.fugazi.app.data.UsageRepository
import com.fugazi.app.logic.TripEvaluator
import com.fugazi.app.notify.InterventionNotifier
import java.time.LocalDate
import java.time.ZoneId

/**
 * The once-or-twice-daily pass: collect yesterday's passive stats, update the
 * baseline, evaluate the trip, and fire the single intervention if it trips.
 *
 * It does nothing visible for weeks. That is the design.
 */
class DailyRadarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SetupStore(applicationContext)
        val cfg = store.read()

        // Nothing to do until the user has done the one-time good-day setup.
        if (!cfg.setupDone) return Result.success()

        val repo = UsageRepository(applicationContext)
        if (!repo.hasUsageAccess()) {
            // Blindness is not "all good" — surface it (dropout-as-signal).
            InterventionNotifier.notifyBlind(applicationContext)
            return Result.success()
        }

        val dao = AppDatabase.get(applicationContext).dailyUsageDao()

        // Collect the most recent complete day.
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        val stats = repo.collectForDay(yesterday, cfg.lateStartHour, cfg.lateEndHour)
        dao.upsert(
            DailyUsage(
                epochDay = yesterday.toEpochDay(),
                totalMinutes = stats.totalMinutes,
                lateNightMinutes = stats.lateNightMinutes,
                computedAt = System.currentTimeMillis(),
            ),
        )

        val history = dao.recent(60)
        when (val result = TripEvaluator.evaluate(history, cfg.lastTripEpochDay)) {
            is TripEvaluator.Result.Trip -> {
                InterventionNotifier.notifyIntervention(applicationContext, cfg.keystone, cfg.message)
                store.markTrip(yesterday.toEpochDay())
            }
            else -> Unit // NotEnoughData / Quiet / Cooldown -> stay silent.
        }

        return Result.success()
    }
}
