package com.fugazi.app.sense

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Pure decisions for auto-marked habits. Given what the sensors saw, is today done, not
 * done (but worth writing down), or not decidable yet?
 */
sealed interface Decision {
    data class Done(val text: String) : Decision
    data class Observed(val text: String) : Decision
    data object Pending : Decision
}

/**
 * No phone for [minutes] after waking. An unlock inside the window means not done; an unlock
 * after it — or no unlock at all once the window has passed — means done.
 */
fun decideNoPhone(wake: LocalDateTime, firstUnlock: LocalDateTime?, now: LocalDateTime, minutes: Int): Decision {
    val windowEnd = wake.plusMinutes(minutes.toLong())
    if (firstUnlock != null) {
        val gap = Duration.between(wake, firstUnlock).toMinutes().coerceAtLeast(0)
        val text = "woke ${hm(wake)} · first unlock ${hm(firstUnlock)} ($gap min)"
        return if (firstUnlock.isBefore(windowEnd)) Decision.Observed(text) else Decision.Done(text)
    }
    return if (!now.isBefore(windowEnd)) Decision.Done("woke ${hm(wake)} · no unlock in the first $minutes min")
    else Decision.Pending
}

/**
 * Woke within [minutes] of the usual time, where usual is the median of [prior] wake times.
 * Needs a few nights before "usual" means anything.
 */
fun decideSchedule(wake: LocalDateTime, prior: List<LocalDateTime>, minutes: Int): Decision {
    if (prior.size < MIN_BASELINE_NIGHTS) {
        return Decision.Observed("woke ${hm(wake)} · learning your usual time (${prior.size}/$MIN_BASELINE_NIGHTS nights)")
    }
    val usual = medianMinuteOfDay(prior.map { it.hour * 60 + it.minute })
    val off = circularDiff(wake.hour * 60 + wake.minute, usual)
    val text = "woke ${hm(wake)} · usual ${hm(usual)} ($off min off)"
    return if (off <= minutes) Decision.Done(text) else Decision.Observed(text)
}

const val MIN_BASELINE_NIGHTS = 3

/**
 * A continuous walk of at least [minutes]. Today stays undecided until it happens (you might
 * still go out); a finished day without one is written down as observed.
 */
fun decideWalk(day: DayMovement?, minutes: Int, dayOver: Boolean): Decision {
    val text = day?.let { "longest walk ${it.longestWalkMin} min · ${it.steps} steps" }
    return when {
        day != null && day.longestWalkMin >= minutes -> Decision.Done(text!!)
        dayOver && day != null -> Decision.Observed(text!!)
        else -> Decision.Pending
    }
}

/**
 * Median time of day on a circle, so 23:50 and 00:10 average to midnight rather than noon.
 * Rotates the circle to start at the biggest gap between samples, then takes a plain median.
 */
internal fun medianMinuteOfDay(values: List<Int>): Int {
    val s = values.map { ((it % DAY) + DAY) % DAY }.sorted()
    var cut = 0
    var widest = -1
    for (i in s.indices) {
        val next = if (i == s.lastIndex) s[0] + DAY else s[i + 1]
        if (next - s[i] > widest) { widest = next - s[i]; cut = (i + 1) % s.size }
    }
    val rotated = (s.drop(cut) + s.take(cut).map { it + DAY })
    return rotated[rotated.size / 2] % DAY
}

internal fun circularDiff(a: Int, b: Int): Int {
    val d = abs(a - b) % DAY
    return minOf(d, DAY - d)
}

private const val DAY = 24 * 60
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(t: LocalDateTime) = t.format(HM)
private fun hm(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
