package com.fugazi.app.sense

import android.content.Context
import com.fugazi.app.data.UsageRepository
import com.fugazi.app.journal.AutoType
import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.JournalStore
import com.fugazi.app.journal.SRC_AUTO
import com.fugazi.app.journal.nowStamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Marks auto habits from the wearable (sleep, steps) and the phone (first unlock). Runs from
 * the background check and whenever the app opens.
 *
 * Each (habit, day) is decided once: if anything is already logged for that day — your own
 * tap, an undo, a skip, or an earlier auto decision — it's left alone. Fitbit can sync hours
 * late, so it looks back a week for days it hasn't decided yet.
 */
object AutoMarker {

    /** What the settings screen shows so you can tell whether data is arriving. */
    data class Status(val ready: Boolean, val note: String, val lastWake: LocalDateTime? = null)

    suspend fun run(ctx: Context, now: LocalDateTime = LocalDateTime.now()): Status {
        if (!Sleep.available(ctx)) return Status(false, "Health Connect isn't available on this phone.")
        val granted = Sleep.granted(ctx)
        if (granted.isEmpty()) return Status(false, "Health Connect access not granted yet.")

        val source = SenseSettings.source(ctx)
        val zone = ZoneId.systemDefault()
        val today = now.toLocalDate()
        val from = today.minusDays(BASELINE_DAYS).atStartOfDay(zone).toInstant()
        val to = now.atZone(zone).toInstant()
        val wakes = try {
            if (Sleep.READ in granted) Sleep.wakeTimes(ctx, source, from, to) else emptyMap()
        } catch (_: SecurityException) {
            // Background read not granted: fine, the next app open will catch up.
            return Status(false, "Couldn't read Health Connect in the background — open the app to catch up.")
        }
        val moves = runCatching {
            Movement.days(ctx, source, today.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant(), to)
        }.getOrDefault(emptyMap())

        val usage = UsageRepository(ctx).hasUsageAccess()
        val events = JournalStore.events.value
        val out = mutableListOf<Event>()
        for (h in JournalStore.habits.value.filter { !it.archived && it.auto != null }) {
            val rule = h.auto ?: continue
            val created = LocalDate.parse(h.created)
            for (back in LOOKBACK_DAYS downTo 0) {
                val day = today.minusDays(back)
                if (day.isBefore(created)) continue
                if (events.any { it.habit == h.id && it.date == day.toString() && it.type in DECIDED }) continue
                val decision = when (rule.type) {
                    AutoType.WAKE_NO_PHONE -> {
                        val wake = wakes[day] ?: continue
                        if (!usage) continue // can't see unlocks; never guess "done"
                        decideNoPhone(wake, Unlocks.firstAfter(ctx, wake, wake.plusHours(12)), now, rule.minutes)
                    }
                    AutoType.WAKE_ON_SCHEDULE -> {
                        val wake = wakes[day] ?: continue
                        val prior = wakes.filterKeys { it < day && it >= day.minusDays(BASELINE_DAYS) }.values.toList()
                        decideSchedule(wake, prior, rule.minutes)
                    }
                    AutoType.WALK -> decideWalk(moves[day], rule.minutes, dayOver = day < today)
                }
                when (decision) {
                    is Decision.Done -> out += auto(h.id, EventType.DONE, day, decision.text)
                    is Decision.Observed -> out += auto(h.id, EventType.OBSERVED, day, decision.text)
                    Decision.Pending -> Unit
                }
            }
        }
        JournalStore.appendAll(out)
        val last = wakes.maxByOrNull { it.key }?.value
        val note = when {
            wakes.isEmpty() && moves.isEmpty() -> "Connected, but nothing from ${source.label} in the last $BASELINE_DAYS days."
            else -> "Reading from ${source.label}."
        }
        return Status(true, note, last)
    }

    private fun auto(habit: String, type: EventType, day: LocalDate, text: String) =
        Event(t = nowStamp(), habit = habit, type = type, date = day.toString(), text = text, src = SRC_AUTO)

    private val DECIDED = setOf(EventType.DONE, EventType.UNDONE, EventType.SKIP, EventType.OBSERVED)
    private const val LOOKBACK_DAYS = 6L
    private const val BASELINE_DAYS = 21L
}
