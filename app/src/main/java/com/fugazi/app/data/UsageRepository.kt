package com.fugazi.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Per-day passive screen stats, in minutes. */
data class DayStats(val totalMinutes: Long, val lateNightMinutes: Long)

/**
 * Reads screen-time signals from [UsageStatsManager]. This is the radar that
 * keeps working whether or not the user engages with the app — the entire point.
 *
 * Everything here is derived from foreground app intervals; we never ask the
 * user for anything.
 */
class UsageRepository(private val context: Context) {

    /** Whether the user has granted "Usage access" in system settings. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Collect stats for a single calendar [date]. The late-night window runs from
     * [lateStartHour] on [date] to [lateEndHour] the following morning (e.g. 23:00 -> 03:00),
     * which is where the phone-on-bed loop lives.
     */
    fun collectForDay(date: LocalDate, lateStartHour: Int, lateEndHour: Int): DayStats {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val lateStart = date.atTime(lateStartHour, 0).atZone(zone).toInstant().toEpochMilli()
        // If the end hour is not after the start hour, the window crosses midnight.
        val lateEndDate = if (lateEndHour <= lateStartHour) date.plusDays(1) else date
        val lateEnd = lateEndDate.atTime(lateEndHour, 0).atZone(zone).toInstant().toEpochMilli()

        // Query a little before the day starts so sessions already open at midnight
        // are captured, and through the end of the late-night window.
        val queryStart = dayStart - 6 * 60 * 60 * 1000L
        val queryEnd = maxOf(dayEnd, lateEnd)
        val intervals = foregroundIntervals(queryStart, queryEnd)

        return DayStats(
            totalMinutes = overlapMinutes(intervals, dayStart, dayEnd),
            lateNightMinutes = overlapMinutes(intervals, lateStart, lateEnd),
        )
    }

    /** Bootstrap the baseline by collecting the last [days] days at setup time. */
    fun backfill(days: Int, lateStartHour: Int, lateEndHour: Int): List<DailyUsage> {
        val today = LocalDate.now(ZoneId.systemDefault())
        return (1..days).map { offset ->
            val date = today.minusDays(offset.toLong())
            val s = collectForDay(date, lateStartHour, lateEndHour)
            DailyUsage(date.toEpochDay(), s.totalMinutes, s.lateNightMinutes, System.currentTimeMillis())
        }
    }

    /** Merged foreground intervals (ms) within [start, end). */
    private fun foregroundIntervals(start: Long, end: Long): List<LongRange> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end)
        val open = HashMap<String, Long>()
        val raw = ArrayList<LongRange>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    open[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val s = open.remove(event.packageName)
                    if (s != null && event.timeStamp > s) raw.add(s..event.timeStamp)
                }
            }
        }
        // Close any sessions still open at the end of the window.
        for ((_, s) in open) if (end > s) raw.add(s..end)

        return mergeIntervals(raw)
    }

    private fun mergeIntervals(intervals: List<LongRange>): List<LongRange> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val merged = ArrayList<LongRange>()
        var curStart = sorted[0].first
        var curEnd = sorted[0].last
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            if (r.first <= curEnd) {
                if (r.last > curEnd) curEnd = r.last
            } else {
                merged.add(curStart..curEnd)
                curStart = r.first
                curEnd = r.last
            }
        }
        merged.add(curStart..curEnd)
        return merged
    }

    private fun overlapMinutes(intervals: List<LongRange>, windowStart: Long, windowEnd: Long): Long {
        var ms = 0L
        for (r in intervals) {
            val lo = maxOf(r.first, windowStart)
            val hi = minOf(r.last, windowEnd)
            if (hi > lo) ms += hi - lo
        }
        return ms / 60000L
    }
}
