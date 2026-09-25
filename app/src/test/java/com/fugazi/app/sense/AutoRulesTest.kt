package com.fugazi.app.sense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoRulesTest {

    private val wake = LocalDateTime.of(2026, 9, 26, 7, 50)

    @Test fun unlockInsideWindowIsNotDone() {
        val d = decideNoPhone(wake, wake.plusMinutes(2), wake.plusHours(3), 30)
        assertTrue(d is Decision.Observed)
        assertTrue((d as Decision.Observed).text.contains("(2 min)"))
    }

    @Test fun unlockAfterWindowIsDone() {
        assertTrue(decideNoPhone(wake, wake.plusMinutes(45), wake.plusHours(3), 30) is Decision.Done)
    }

    @Test fun noUnlockYetWaitsUntilWindowPasses() {
        assertEquals(Decision.Pending, decideNoPhone(wake, null, wake.plusMinutes(10), 30))
        assertTrue(decideNoPhone(wake, null, wake.plusMinutes(31), 30) is Decision.Done)
    }

    @Test fun scheduleNeedsABaselineFirst() {
        assertTrue(decideSchedule(wake, listOf(wake.minusDays(1)), 60) is Decision.Observed)
    }

    @Test fun scheduleComparesToMedianWake() {
        val prior = listOf(7 to 30, 8 to 0, 7 to 45, 12 to 0).map { (h, m) -> wake.minusDays(1).withHour(h).withMinute(m) }
        // median of 07:30, 07:45, 08:00, 12:00 is 08:00 (upper middle); 07:50 is 10 min off
        assertTrue(decideSchedule(wake, prior, 60) is Decision.Done)
        assertTrue(decideSchedule(wake.withHour(10), prior, 60) is Decision.Observed)
    }

    @Test fun medianWrapsAroundMidnight() {
        assertEquals(0, medianMinuteOfDay(listOf(23 * 60 + 50, 0, 10)))
        assertEquals(20, circularDiff(23 * 60 + 50, 10))
    }
}

class WalkTest {
    private val t0 = java.time.Instant.parse("2026-09-26T08:00:00Z")
    private fun span(startMin: Long, lenMin: Long, steps: Long) =
        StepSpan(t0.plusSeconds(startMin * 60), t0.plusSeconds((startMin + lenMin) * 60), steps)

    @Test fun joinsConsecutiveWalkingMinutes() {
        val runs = walkingRuns((0L until 25L).map { span(it, 1, 100) })
        assertEquals(1, runs.size)
        assertEquals(25, java.time.Duration.between(runs[0].first, runs[0].second).toMinutes())
    }

    @Test fun slowPotteringIsNotAWalk() {
        assertTrue(walkingRuns((0L until 30L).map { span(it, 1, 20) }).isEmpty())
    }

    @Test fun aLongPauseSplitsTheWalk() {
        val runs = walkingRuns((0L until 10L).map { span(it, 1, 100) } + (15L until 25L).map { span(it, 1, 100) })
        assertEquals(2, runs.size)
    }

    @Test fun coarseBucketsDontCountAsWalks() {
        assertTrue(walkingRuns(listOf(span(0, 60, 6000))).isEmpty())
    }

    @Test fun walkRuleWaitsForTodayButRecordsFinishedDays() {
        assertTrue(decideWalk(DayMovement(9000, 32), 20, dayOver = false) is Decision.Done)
        assertEquals(Decision.Pending, decideWalk(DayMovement(3000, 5), 20, dayOver = false))
        assertTrue(decideWalk(DayMovement(3000, 5), 20, dayOver = true) is Decision.Observed)
        assertEquals(Decision.Pending, decideWalk(null, 20, dayOver = true))
    }
}

class RadarTest {
    private fun day(n: Int, late: Long, total: Long) =
        DaySignals(date = java.time.LocalDate.of(2026, 9, 1).plusDays(n.toLong()).toString(), lateNightMin = late, screenMin = total)

    @Test fun firesOnTwoLateNightsThenCoolsDown() {
        val calm = (0 until 12).map { day(it, 20, 200) }
        val bad = calm + day(12, 90, 300) + day(13, 120, 320)
        val today = java.time.LocalDate.of(2026, 9, 15)
        val e = Radar.evaluate(bad, emptyList(), today)
        assertEquals(Radar.ID, e?.habit)
        org.junit.Assert.assertNull(Radar.evaluate(bad, listOf(e!!), today))
        org.junit.Assert.assertNull(Radar.evaluate(calm, emptyList(), today))
    }
}

class RadarUnknownDaysTest {
    @Test fun daysWithoutUsageDataDontCountAsZero() {
        // Unknown days (null) are left out, so a thin history means "not enough data", not a trip.
        val days = (0 until 12).map { DaySignals(date = java.time.LocalDate.of(2026, 9, 1).plusDays(it.toLong()).toString()) } +
            DaySignals(date = "2026-09-13", lateNightMin = 90, screenMin = 300) +
            DaySignals(date = "2026-09-14", lateNightMin = 131, screenMin = 594)
        org.junit.Assert.assertNull(Radar.evaluate(days, emptyList(), java.time.LocalDate.of(2026, 9, 15)))
    }
}
