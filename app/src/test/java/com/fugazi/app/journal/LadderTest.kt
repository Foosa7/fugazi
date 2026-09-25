package com.fugazi.app.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LadderTest {

    private val start = LocalDate.of(2026, 9, 1)

    private val vitB = Habit(
        id = "vitb",
        name = "Vitamin B",
        created = start.toString(),
        ladder = listOf(
            Rung(days = 3, meaning = "Fine."),
            Rung(days = 7, meaning = "Concerning.", ask = "Busy mornings?"),
            Rung(days = 10, meaning = "Off.", ask = "What changed?"),
        ),
    )

    private val scroll = Habit(
        id = "s",
        name = "Doomscrolling",
        kind = Kind.AVOID,
        created = start.toString(),
        ladder = listOf(Rung(days = 7, slips = 3, meaning = "Slipping.", ask = "What's going on at night?")),
    )

    private fun day(n: Int) = start.plusDays(n.toLong())
    private fun ev(h: String, type: EventType, n: Int, text: String? = null) =
        Event(t = "x", habit = h, type = type, date = day(n).toString(), text = text)

    private fun fire(h: Habit, events: MutableList<Event>, n: Int): Event? =
        nextTrigger(reduce(h, events, day(n)), events, day(n))?.also { events += it }

    @Test fun lapseCountsDaysSinceLastDoneAndIgnoresSkips() {
        val es = listOf(ev("vitb", EventType.DONE, 0), ev("vitb", EventType.SKIP, 2, "sick"))
        val s = reduce(vitB, es, day(5))
        assertEquals(4, s.lapse) // days 1,3,4,5 — day 2 was skipped
        assertEquals(0, s.rung) // past the 3-day "fine" label
    }

    @Test fun labelOnlyRungNeverFires() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        assertNull(fire(vitB, es, 4))
    }

    @Test fun rungFiresOncePerLapseAndHigherRungStillFires() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        assertEquals(1, fire(vitB, es, 7)?.rung)
        assertNull(fire(vitB, es, 8))
        assertEquals(2, fire(vitB, es, 10)?.rung)
        assertNull(fire(vitB, es, 11))
    }

    @Test fun jumpingStraightToTopRungDoesNotBackfillLowerOne() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        assertEquals(2, fire(vitB, es, 12)?.rung)
        assertNull(fire(vitB, es, 13))
    }

    @Test fun doingItResetsTheEpisode() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        assertNotNull(fire(vitB, es, 7))
        es += ev("vitb", EventType.DONE, 8)
        assertNull(fire(vitB, es, 9))
        assertEquals(1, fire(vitB, es, 15)?.rung)
    }

    @Test fun undoRemovesADone() {
        val es = listOf(ev("vitb", EventType.DONE, 3), ev("vitb", EventType.UNDONE, 3))
        assertEquals(emptySet<LocalDate>(), reduce(vitB, es, day(4)).done)
    }

    @Test fun doWinsAfterAWeekOnPaceThenWaitsAWeek() {
        val es = mutableListOf<Event>()
        (0..6).forEach { es += ev("vitb", EventType.DONE, it) }
        val win = fire(vitB, es, 6)
        assertEquals(WIN_RUNG, win?.rung)
        es += ev("vitb", EventType.DONE, 7)
        assertNull(fire(vitB, es, 7))
        (8..13).forEach { es += ev("vitb", EventType.DONE, it) }
        assertEquals(WIN_RUNG, fire(vitB, es, 13)?.rung)
    }

    @Test fun weeklyTargetWinsOnPace() {
        val twiceAWeek = vitB.copy(target = Target(times = 2, days = 7), ladder = emptyList())
        val es = mutableListOf(ev("vitb", EventType.DONE, 2), ev("vitb", EventType.DONE, 5))
        assertEquals(WIN_RUNG, fire(twiceAWeek, es, 6)?.rung)
    }

    @Test fun avoidRungFiresOnSlipDensity() {
        val es = mutableListOf(ev("s", EventType.SLIP, 1), ev("s", EventType.SLIP, 3))
        assertNull(fire(scroll, es, 3))
        es += ev("s", EventType.SLIP, 5)
        assertEquals(0, fire(scroll, es, 5)?.rung)
        assertNull(fire(scroll, es, 6))
    }

    @Test fun avoidCelebratesCleanMilestonesOnce() {
        val es = mutableListOf(ev("s", EventType.SLIP, 0))
        assertNull(fire(scroll, es, 6))
        val w7 = fire(scroll, es, 7)
        assertEquals(7, w7?.days)
        assertNull(fire(scroll, es, 8))
        assertEquals(14, fire(scroll, es, 14)?.days)
        // A slip starts a new clean run; 7 days later it's worth noticing again.
        es += ev("s", EventType.SLIP, 15)
        assertEquals(7, fire(scroll, es, 22)?.days)
    }

    @Test fun answeringClosesACheckIn() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        val t = fire(vitB, es, 7)!!
        assertEquals(1, pendingTriggers(es, day(7)).size)
        es += Event(t = "y", habit = "vitb", type = EventType.ANSWER, rung = t.rung, text = "Busy mornings")
        assertEquals(0, pendingTriggers(es, day(7)).size)
    }

    @Test fun unansweredCheckInExpires() {
        val es = mutableListOf(ev("vitb", EventType.DONE, 0))
        fire(vitB, es, 7)
        assertEquals(0, pendingTriggers(es, day(7 + PENDING_TTL_DAYS.toInt())).size)
    }

    @Test fun beforeEveningTheLadderJudgesYesterday() {
        assertEquals(day(4), evalDay(LocalDateTime.of(day(5), java.time.LocalTime.of(9, 0))))
        assertEquals(day(5), evalDay(LocalDateTime.of(day(5), java.time.LocalTime.of(EVAL_HOUR, 0))))
    }

    @Test fun jsonlRoundTripKeepsTheMeta() {
        val line = JournalStore.json.encodeToString(Habit.serializer(), vitB)
        assertEquals(vitB, JournalStore.json.decodeFromString(Habit.serializer(), line))
        assert("\"kind\"" !in line) // defaults stay out of the file
    }
}
