package com.fugazi.app.logic

import com.fugazi.app.data.DailyUsage
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEvaluatorTest {

    private fun day(epoch: Long, total: Long, late: Long) =
        DailyUsage(epoch, total, late, computedAt = 0)

    /** A calm baseline followed by two elevated late nights + higher total -> Trip. */
    @Test
    fun trips_on_two_elevated_late_nights_and_higher_total() {
        val history = buildList {
            for (d in 0 until 14) add(day(d.toLong(), total = 180, late = 10))
            add(day(14, total = 230, late = 60))
            add(day(15, total = 240, late = 70))
        }
        val result = TripEvaluator.evaluate(history, lastTripEpochDay = -1)
        assertTrue("expected Trip but was $result", result is TripEvaluator.Result.Trip)
    }

    /** Steady usage stays quiet. */
    @Test
    fun stays_quiet_on_steady_usage() {
        val history = (0 until 16).map { day(it.toLong(), total = 180, late = 10) }
        val result = TripEvaluator.evaluate(history, lastTripEpochDay = -1)
        assertTrue("expected Quiet but was $result", result is TripEvaluator.Result.Quiet)
    }

    /** Only one elevated night is not enough. */
    @Test
    fun single_elevated_night_does_not_trip() {
        val history = buildList {
            for (d in 0 until 15) add(day(d.toLong(), total = 180, late = 10))
            add(day(15, total = 240, late = 70))
        }
        val result = TripEvaluator.evaluate(history, lastTripEpochDay = -1)
        assertTrue("expected Quiet but was $result", result is TripEvaluator.Result.Quiet)
    }

    /** Too little history -> NotEnoughData. */
    @Test
    fun not_enough_history() {
        val history = (0 until 4).map { day(it.toLong(), total = 180, late = 10) }
        val result = TripEvaluator.evaluate(history, lastTripEpochDay = -1)
        assertTrue(result is TripEvaluator.Result.NotEnoughData)
    }

    /** Recent trip -> Cooldown, even if signals are elevated. */
    @Test
    fun cooldown_suppresses_repeat() {
        val history = buildList {
            for (d in 0 until 14) add(day(d.toLong(), total = 180, late = 10))
            add(day(14, total = 230, late = 60))
            add(day(15, total = 240, late = 70))
        }
        val result = TripEvaluator.evaluate(history, lastTripEpochDay = 13)
        assertTrue("expected Cooldown but was $result", result is TripEvaluator.Result.Cooldown)
    }
}
