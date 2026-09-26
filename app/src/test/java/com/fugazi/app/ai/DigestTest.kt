package com.fugazi.app.ai

import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.Kind
import com.fugazi.app.sense.DaySignals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DigestTest {
    private val floss = Habit(id = "floss", name = "Floss", why = "Clean teeth", created = "2026-01-01")
    private val scroll = Habit(id = "scroll", name = "Doomscrolling", kind = Kind.AVOID, created = "2026-01-01")
    private val d1 = LocalDate.of(2026, 3, 9)
    private val d2 = d1.plusDays(1)

    private fun digest(events: List<Event>, signals: Map<String, DaySignals> = emptyMap(), writing: Map<LocalDate, String> = emptyMap()) =
        Digest.days(d1, d2, listOf(floss, scroll), events, signals) { writing[it].orEmpty() }

    @Test fun daysAreInOrderWithYesterdayAboveToday() {
        val out = digest(emptyList())
        assertTrue(out.indexOf("2026-03-09 Monday") < out.indexOf("2026-03-10 Tuesday"))
    }

    @Test fun doneMissedSkippedAndSlipsLandOnTheirDay() {
        val out = digest(
            listOf(
                Event(t = "x", habit = "floss", type = EventType.DONE, date = "$d1"),
                Event(t = "x", habit = "floss", type = EventType.SKIP, date = "$d2", text = "sick"),
                Event(t = "x", habit = "scroll", type = EventType.SLIP, date = "$d2"),
            ),
        )
        val (first, second) = out.split("## 2026-03-10").let { it[0] to it[1] }
        assertTrue(first.contains("done: Floss"))
        assertTrue(second.contains("Floss (skipped: sick)"))
        assertTrue(second.contains("slipped: Doomscrolling"))
        assertFalse(first.contains("slipped"))
    }

    @Test fun signalsAndWritingAreIncludedButUnknownIsNotZero() {
        val out = digest(
            emptyList(),
            signals = mapOf("$d1" to DaySignals(date = "$d1", wake = "08:14:00", lateNightMin = 44)),
            writing = mapOf(d2 to "went for a run"),
        )
        assertTrue(out.contains("woke 08:14"))
        assertTrue(out.contains("44 min phone"))
        assertFalse(out.contains("0 steps"))
        assertTrue(out.contains("writing:\nwent for a run"))
    }

    @Test fun habitsCarryTheirWhy() {
        assertTrue(Digest.habits(listOf(floss)).contains("why: Clean teeth"))
    }

    @Test fun statesShowAsFeltWithTheirNoteAndMusicComparison() {
        val calm = Habit(id = "calm", name = "Calm", kind = Kind.STATE, created = "2026-01-01")
        val events = listOf(Event(t = "x", habit = "calm", type = EventType.DONE, date = "$d1", text = "after a run"))
        val signals = mapOf(
            "$d1" to DaySignals(date = "$d1", tracks = 10, skips = 1),
            "$d2" to DaySignals(date = "$d2", tracks = 10, skips = 6),
        )
        val out = Digest.days(d1, d2, listOf(calm), events, signals) { "" }
        assertTrue(out.contains("felt: Calm"))
        assertTrue(out.contains("Calm, your note: after a run"))
        assertFalse(out.contains("not done: Calm"))
        val sum = Digest.stateSummary(calm, events, signals, d1, d2)
        assertTrue(sum.contains("on days they felt it 10% of songs skipped over 1 day"))
        assertTrue(sum.contains("other days 60% of songs skipped over 1 day"))
    }

    @Test fun tappedIngredientsAreShownAndCounted() {
        val calm = Habit(id = "calm", name = "Calm", kind = Kind.STATE, created = "2026-01-01")
        val events = listOf(
            Event(t = "x", habit = "calm", type = EventType.DONE, date = "$d1", tags = listOf("Hard effort", "No phone")),
            Event(t = "x", habit = "calm", type = EventType.DONE, date = "$d2", tags = listOf("Hard effort"), text = "long ride"),
        )
        val out = Digest.days(d1, d2, listOf(calm), events, emptyMap()) { "" }
        assertTrue(out.contains("Calm, your note: Hard effort, No phone"))
        assertTrue(out.contains("Calm, your note: Hard effort — long ride"))
        val sum = Digest.stateSummary(calm, events, emptyMap(), d1, d2)
        assertTrue(sum, sum.contains("Hard effort ×2, No phone ×1"))
    }
}
