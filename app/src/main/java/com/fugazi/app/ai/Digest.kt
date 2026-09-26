package com.fugazi.app.ai

import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.Kind
import com.fugazi.app.journal.reduce
import com.fugazi.app.sense.DaySignals
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Turns the journal into the text a model reads: one block per day, oldest first, so that
 * "yesterday" is literally the block above "today". Each block puts everything about the
 * day together (what the phone saw, what you did and didn't do and why, your answers, your
 * writing), because causes only show up when they sit next to each other in time.
 *
 * Pure, so it can be tested without a phone.
 */
object Digest {

    /** The habits and what each one is *for*, written once instead of repeated per day. */
    fun habits(habits: List<Habit>): String = buildString {
        habits.filter { !it.archived }.forEach { h ->
            append("- ").append(h.id).append(": ").append(h.name)
            if (h.kind == Kind.AVOID) append(" (avoid)")
            if (h.kind == Kind.STATE) append(" (a state of mind they want to live in; they tap days they felt it, and days without it are not misses)")
            if (h.target.times != 1 || h.target.days != 1) append(" — ${h.target.times}× per ${h.target.days} days")
            append('\n')
            if (h.why.isNotBlank()) append("  why: ").append(h.why.oneLine()).append('\n')
            if (h.goal.isNotBlank()) append("  goal: ").append(h.goal.oneLine()).append('\n')
        }
    }

    fun days(
        from: LocalDate,
        to: LocalDate,
        habits: List<Habit>,
        events: List<Event>,
        signals: Map<String, DaySignals>,
        thoughts: (LocalDate) -> String,
    ): String = buildString {
        val active = habits.filter { !it.archived }
        val states = active.map { reduce(it, events, to) }
        var d = from
        while (!d.isAfter(to)) {
            append(day(d, active, states.map { Triple(it.done, it.slips, it.skips) }, events, signals[d.toString()], thoughts(d)))
            append('\n')
            d = d.plusDays(1)
        }
    }

    private fun day(
        d: LocalDate,
        habits: List<Habit>,
        states: List<Triple<Set<LocalDate>, Set<LocalDate>, Map<LocalDate, String>>>,
        events: List<Event>,
        s: DaySignals?,
        thoughts: String,
    ): String = buildString {
        append("## ").append(d.format(HEADER)).append('\n')
        signals(s)?.let { append("phone/wearable: ").append(it).append('\n') }

        val done = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val slipped = mutableListOf<String>()
        val felt = mutableListOf<String>()
        habits.forEachIndexed { i, h ->
            if (h.created > d.toString()) return@forEachIndexed
            val (doneDays, slipDays, skipDays) = states[i]
            skipDays[d]?.let { missed += "${h.name} (skipped: ${it.oneLine()})"; return@forEachIndexed }
            when (h.kind) {
                Kind.DO -> if (d in doneDays) done += h.name else if (h.target.days == 1) missed += h.name
                Kind.AVOID -> if (d in slipDays) slipped += h.name
                Kind.STATE -> if (d in doneDays) felt += h.name
            }
        }
        if (done.isNotEmpty()) append("done: ").append(done.joinToString(", ")).append('\n')
        if (missed.isNotEmpty()) append("not done: ").append(missed.joinToString(", ")).append('\n')
        if (felt.isNotEmpty()) append("felt: ").append(felt.joinToString(", ")).append('\n')
        if (slipped.isNotEmpty()) append("slipped: ").append(slipped.joinToString(", ")).append('\n')

        // Notes the phone and you attached to this day: what an auto rule saw, check-in answers.
        val byId = habits.associateBy { it.id }
        events.filter { it.date == d.toString() && (!it.text.isNullOrBlank() || it.tags.isNotEmpty()) }
            .filter { it.type in NOTED }
            .forEach { e ->
                val name = byId[e.habit]?.name ?: e.habit
                val who = when {
                    e.type == EventType.TRIGGER -> "check-in fired"
                    e.type == EventType.ANSWER -> "you answered"
                    e.type == EventType.DONE && e.src == null -> "your note"
                    else -> "seen"
                }
                append("- ").append(name).append(", ").append(who).append(": ")
                    .append(listOfNotNull(e.tags.takeIf { it.isNotEmpty() }?.joinToString(", "), e.text?.oneLine()?.ifBlank { null }).joinToString(" — "))
                    .append('\n')
            }

        if (thoughts.isNotBlank()) append("writing:\n").append(thoughts.trim()).append('\n')
    }

    /**
     * For a state habit: the days they felt it, and whether the music signal agrees — they
     * skip fewer songs when they're present. Compares the skip rate on felt days with the
     * other days, counting only days with enough songs to mean anything.
     */
    fun stateSummary(
        h: Habit,
        events: List<Event>,
        signals: Map<String, DaySignals>,
        from: LocalDate,
        to: LocalDate,
    ): String = buildString {
        val felt = reduce(h, events, to).done.filter { !it.isBefore(from) && !it.isAfter(to) }.toSet()
        append(h.name).append('\n')
        if (h.why.isNotBlank()) append("in their words: ").append(h.why.oneLine()).append('\n')
        if (h.goal.isNotBlank()) append("goal: ").append(h.goal.oneLine()).append('\n')
        append("days they tapped it in this window: ")
            .append(if (felt.isEmpty()) "none" else felt.sorted().joinToString(", ")).append('\n')
        // What they said put them there, counted: the start of their recipe, in their own terms.
        val tags = events.filter { it.habit == h.id && it.type == EventType.DONE && it.date?.let(LocalDate::parse) in felt }
            .groupBy { it.date }.values.map { it.last().tags }.flatten()
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (tags.isNotEmpty()) {
            append("what they tapped as putting them there: ")
                .append(tags.joinToString(", ") { "${it.key} ×${it.value}" }).append('\n')
        }

        val music = signals.values.mapNotNull { s ->
            val d = LocalDate.parse(s.date)
            val started = s.tracks ?: return@mapNotNull null
            if (d.isBefore(from) || d.isAfter(to) || started < MIN_TRACKS) return@mapNotNull null
            Triple(d in felt, started, s.skips ?: 0)
        }
        fun rate(on: Boolean): String? {
            val days = music.filter { it.first == on }
            if (days.isEmpty()) return null
            val pct = 100 * days.sumOf { it.third } / days.sumOf { it.second }
            return "$pct% of songs skipped over ${days.size} ${if (days.size == 1) "day" else "days"}"
        }
        val on = rate(true)
        val off = rate(false)
        if (on != null || off != null) {
            append("music (they skip less when they're present): on days they felt it ")
                .append(on ?: "no data").append("; other days ").append(off ?: "no data").append('\n')
        }
    }

    private const val MIN_TRACKS = 5

    private fun signals(s: DaySignals?): String? {
        s ?: return null
        val parts = listOfNotNull(
            s.wake?.let { "woke ${it.take(5)}" },
            s.firstUnlock?.let { "first unlock ${it.take(5)}" },
            s.screenMin?.let { "screen ${it} min" },
            s.lateNightMin?.let { "${it} min phone 23:00–03:00" },
            s.unlocks?.let { "$it unlocks" },
            s.steps?.let { "$it steps" },
            s.longestWalkMin?.takeIf { it > 0 }?.let { "longest walk $it min" },
            s.tracks?.takeIf { it > 0 }?.let { "skipped ${s.skips ?: 0} of $it songs, ${s.longForm ?: 0} long plays" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private val NOTED = setOf(EventType.OBSERVED, EventType.TRIGGER, EventType.ANSWER, EventType.DONE)
    private val HEADER = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE")
    private fun String.oneLine() = replace('\n', ' ').trim()
}
