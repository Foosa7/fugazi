package com.fugazi.app.logic

import com.fugazi.app.data.DailyUsage

/**
 * The trip condition. Pure function of the user's own history — no absolute
 * thresholds, no manual input. Fires when the leading edge of the valley shows
 * up in passive signals:
 *
 *   late-night usage elevated for N consecutive nights vs the user's own
 *   trailing median, AND total daily usage up vs that same baseline.
 *
 * Silence-as-signal: this never needs the user to confirm anything. The only
 * way it stays quiet is if the passive numbers genuinely look normal.
 */
object TripEvaluator {

    data class Config(
        val minHistoryDays: Int = 7,
        val baselineWindow: Int = 14,
        val consecutiveNights: Int = 2,
        val lateFactor: Double = 1.3,
        val lateMarginMin: Long = 10,
        val lateFloorMin: Long = 15,
        val totalFactor: Double = 1.15,
        val cooldownDays: Int = 7,
    )

    sealed interface Result {
        /** Not enough baseline history yet to judge anything. */
        data object NotEnoughData : Result
        /** Numbers look like the user's normal. Stay silent. */
        data object Quiet : Result
        /** Already fired recently; don't nag during the climb. */
        data object Cooldown : Result
        /** The leading edge is here. Fire the one intervention. */
        data class Trip(val reason: String) : Result
    }

    fun evaluate(
        history: List<DailyUsage>,
        lastTripEpochDay: Long,
        cfg: Config = Config(),
    ): Result {
        val sorted = history.sortedBy { it.epochDay }
        if (sorted.size < cfg.minHistoryDays) return Result.NotEnoughData

        val recent = sorted.takeLast(cfg.consecutiveNights)
        if (recent.size < cfg.consecutiveNights) return Result.NotEnoughData

        val latestDay = sorted.last().epochDay
        if (lastTripEpochDay >= 0 && latestDay - lastTripEpochDay < cfg.cooldownDays) {
            return Result.Cooldown
        }

        val baselineDays = sorted.dropLast(cfg.consecutiveNights).takeLast(cfg.baselineWindow)
        if (baselineDays.size < cfg.minHistoryDays - cfg.consecutiveNights) {
            return Result.NotEnoughData
        }

        val baseLate = median(baselineDays.map { it.lateNightMinutes })
        val baseTotal = median(baselineDays.map { it.totalMinutes })

        // Late-night elevated on every one of the last N nights. The floor and
        // margin keep us from tripping on noise when the baseline is near zero.
        val lateElevated = recent.all { day ->
            val threshold = maxOf((baseLate * cfg.lateFactor), (baseLate + cfg.lateMarginMin).toDouble())
            day.lateNightMinutes >= cfg.lateFloorMin && day.lateNightMinutes > threshold
        }
        val totalElevated = sorted.last().totalMinutes > baseTotal * cfg.totalFactor

        return if (lateElevated && totalElevated) {
            Result.Trip(
                "late-night up ${cfg.consecutiveNights} nights " +
                    "(last ${recent.last().lateNightMinutes}m vs base ${baseLate}m); " +
                    "total ${sorted.last().totalMinutes}m vs base ${baseTotal}m",
            )
        } else {
            Result.Quiet
        }
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else ((s[mid - 1] + s[mid]) / 2)
    }
}
