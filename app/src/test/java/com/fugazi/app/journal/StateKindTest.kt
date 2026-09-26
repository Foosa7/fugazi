package com.fugazi.app.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StateKindTest {
    private val today = LocalDate.of(2026, 3, 20)
    private val calm = Habit(
        id = "calm", name = "Calm", kind = Kind.STATE, created = "2026-03-01",
        ladder = listOf(Rung(days = 5, ask = "Where has it been?")),
    )
    private fun felt(d: LocalDate) = Event(t = "x", habit = "calm", type = EventType.DONE, date = "$d", text = "a walk")

    @Test fun neverEarnsAWinStreak() {
        val events = (0L..9L).map { felt(today.minusDays(it)) }
        assertNull(nextTrigger(reduce(calm, events, today), events, today))
    }

    @Test fun asksOnceAfterALongStretchAway() {
        val events = listOf(felt(today.minusDays(6)))
        val t = nextTrigger(reduce(calm, events, today), events, today)
        assertEquals(0, t?.rung)
        assertNull(nextTrigger(reduce(calm, events + t!!, today), events + t, today))
    }
}
