package com.fugazi.app.journal

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Pure functions over (habit, events, today). No Android, no I/O — this is where the rules
 * live, and it's what the unit tests pin down.
 */

/** Days-clean milestones for AVOID habits that earn a "this is going well" check-in. */
val AVOID_WIN_MILESTONES = listOf(7, 14, 30, 60, 90, 180, 365)

/** Days a DO habit must be on pace before it earns a win check-in; also the refire gap. */
const val DO_WIN_WINDOW = 7

/** A trigger nobody answered stops being shown after this many days. */
const val PENDING_TTL_DAYS = 7L

/** What the event log says about one habit, reduced to dates. */
data class HabitState(
    val habit: Habit,
    val done: Set<LocalDate>,
    val slips: Set<LocalDate>,
    val skips: Map<LocalDate, String>,
    /** DO: days since last done, not counting skipped days. AVOID: days since last slip. */
    val lapse: Int,
    /** Index into the ladder of the rung currently reached, or null. */
    val rung: Int?,
    /** 0..1, Loop-style: builds slowly, one missed day barely dents it. */
    val strength: Double,
)

fun reduce(habit: Habit, events: List<Event>, today: LocalDate): HabitState {
    val done = mutableSetOf<LocalDate>()
    val slips = mutableSetOf<LocalDate>()
    val skips = mutableMapOf<LocalDate, String>()
    // File order is write order, so the last toggle for a date wins.
    for (e in events) {
        if (e.habit != habit.id) continue
        val d = e.date?.let(LocalDate::parse) ?: continue
        when (e.type) {
            EventType.DONE -> { done += d; skips -= d }
            EventType.UNDONE -> { done -= d; skips -= d }
            EventType.SLIP -> slips += d
            EventType.UNSLIP -> slips -= d
            EventType.SKIP -> { skips[d] = e.text.orEmpty(); done -= d }
            else -> Unit
        }
    }
    val created = LocalDate.parse(habit.created)
    val lapse = when (habit.kind) {
        Kind.DO -> {
            val anchor = done.filter { it <= today }.maxOrNull() ?: created
            daysBetween(anchor, today).count { it !in skips }
        }
        Kind.AVOID -> {
            val anchor = slips.filter { it <= today }.maxOrNull() ?: created
            daysBetween(anchor, today).size
        }
    }
    val rung = habit.ladder.indices.lastOrNull { i ->
        val r = habit.ladder[i]
        when (habit.kind) {
            Kind.DO -> lapse >= r.days
            Kind.AVOID -> slipsWithin(slips, today, r.days) >= r.slips
        }
    }
    return HabitState(habit, done, slips, skips, lapse, rung, strength(habit, done, slips, skips, today))
}

/**
 * The one new trigger this habit should fire today, if any. Slips come first; a habit
 * mid-lapse isn't also "going well". Each rung fires once per lapse, and reaching a rung
 * never re-fires a lower one.
 */
fun nextTrigger(state: HabitState, events: List<Event>, today: LocalDate): Event? {
    val h = state.habit
    if (h.archived) return null
    val mine = events.filter { it.habit == h.id && it.type == EventType.TRIGGER }
    fun firedSince(after: LocalDate, pred: (Event) -> Boolean) =
        mine.any { e -> e.date?.let(LocalDate::parse)?.let { it > after } == true && pred(e) }

    val reached = state.rung
    if (reached != null && h.ladder[reached].ask != null) {
        val episodeStart = when (h.kind) {
            Kind.DO -> state.done.filter { it <= today }.maxOrNull() ?: LocalDate.parse(h.created)
            Kind.AVOID -> today.minusDays(h.ladder[reached].days.toLong())
        }
        val already = firedSince(episodeStart) { (it.rung ?: WIN_RUNG) >= reached }
        return if (already) null else trigger(h, today, reached, null)
    }

    return when (h.kind) {
        Kind.DO -> {
            val created = LocalDate.parse(h.created)
            val oldEnough = !created.isAfter(today.minusDays((DO_WIN_WINDOW - 1).toLong()))
            val need = ceil(DO_WIN_WINDOW * h.target.times.toDouble() / h.target.days).toInt()
            val got = state.done.count { it > today.minusDays(DO_WIN_WINDOW.toLong()) && it <= today }
            val recent = firedSince(today.minusDays(DO_WIN_WINDOW.toLong())) { it.rung == WIN_RUNG }
            if (oldEnough && got >= need && !recent) trigger(h, today, WIN_RUNG, DO_WIN_WINDOW) else null
        }
        Kind.AVOID -> {
            val milestone = AVOID_WIN_MILESTONES.lastOrNull { it <= state.lapse } ?: return null
            val anchor = today.minusDays(state.lapse.toLong())
            val already = firedSince(anchor) { it.rung == WIN_RUNG && (it.days ?: 0) >= milestone }
            if (!already) trigger(h, today, WIN_RUNG, milestone) else null
        }
    }
}

/** Triggers still waiting for an answer, newest first. */
fun pendingTriggers(events: List<Event>, today: LocalDate): List<Event> {
    val open = mutableListOf<Event>()
    for (e in events) {
        when (e.type) {
            EventType.TRIGGER -> open += e
            EventType.ANSWER, EventType.DISMISS -> open.removeAll { it.habit == e.habit && it.rung == e.rung }
            else -> Unit
        }
    }
    val cutoff = today.minusDays(PENDING_TTL_DAYS)
    return open.filter { e -> e.date?.let(LocalDate::parse)?.let { it > cutoff } == true }.reversed()
}

private fun trigger(h: Habit, today: LocalDate, rung: Int, days: Int?) = Event(
    t = nowStamp(),
    habit = h.id,
    type = EventType.TRIGGER,
    date = today.toString(),
    rung = rung,
    days = days,
)

/** Days in (from, to], oldest first. */
private fun daysBetween(from: LocalDate, to: LocalDate): List<LocalDate> =
    generateSequence(from.plusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

private fun slipsWithin(slips: Set<LocalDate>, today: LocalDate, days: Int): Int {
    val start = today.minusDays(days.toLong())
    return slips.count { it > start && it <= today }
}

/**
 * Loop Habit Tracker's idea: an exponential average with a ~13-day half-life, so strength
 * climbs over weeks and a single missed day costs a few percent, not everything. For a
 * "2 per 7 days" habit a day counts as good while the trailing 7 days hold 2 dones.
 * Skipped days leave it untouched.
 */
private fun strength(
    h: Habit,
    done: Set<LocalDate>,
    slips: Set<LocalDate>,
    skips: Map<LocalDate, String>,
    today: LocalDate,
): Double {
    val keep = 0.5.pow(1.0 / 13)
    var s = 0.0
    var d = LocalDate.parse(h.created)
    while (!d.isAfter(today)) {
        if (d !in skips) {
            val value = when (h.kind) {
                Kind.DO -> {
                    val windowStart = d.minusDays(h.target.days.toLong())
                    val n = done.count { it > windowStart && it <= d }
                    (n.toDouble() / h.target.times).coerceAtMost(1.0)
                }
                Kind.AVOID -> if (d in slips) 0.0 else 1.0
            }
            s = s * keep + value * (1 - keep)
        }
        d = d.plusDays(1)
    }
    return s
}
