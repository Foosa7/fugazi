package com.fugazi.app.sense

import android.content.Context
import com.fugazi.app.data.DailyUsage
import com.fugazi.app.data.SetupStore
import com.fugazi.app.data.UsageRepository
import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.JournalStore
import com.fugazi.app.journal.nowStamp
import com.fugazi.app.logic.TripEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything the phone saw about one day, with no input from you. One line per day in
 * journal/signals.jsonl (a later line for the same date replaces an earlier one), so a
 * model can read it next to the habits and your own words.
 */
@Serializable
data class DaySignals(
    val date: String,
    val screenMin: Long? = null,
    /** Phone use from 23:00 that evening to 03:00 the next morning. */
    val lateNightMin: Long? = null,
    val unlocks: Int? = null,
    val firstUnlock: String? = null,
    val lastUnlock: String? = null,
    val wake: String? = null,
    val steps: Long? = null,
    val longestWalkMin: Int? = null,
    val tracks: Int? = null,
    val skips: Int? = null,
    val longForm: Int? = null,
    val t: String = "",
)

object SignalStore {
    private val mutex = Mutex()
    private lateinit var file: File
    private val _days = MutableStateFlow<Map<String, DaySignals>>(emptyMap())
    val days: StateFlow<Map<String, DaySignals>> = _days

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(File(ctx.filesDir, "journal").apply { mkdirs() }, "signals.jsonl")
        _days.value = if (file.exists()) file.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { JournalStore.json.decodeFromString(DaySignals.serializer(), it) }.getOrNull()
        }.associateBy { it.date } else emptyMap()
    }

    suspend fun put(all: List<DaySignals>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // A retried day that read the same as before would only add a duplicate line.
            val ds = all.filter { d -> _days.value[d.date]?.copy(t = d.t) != d }
            if (ds.isEmpty()) return@withLock
            file.appendText(ds.joinToString("") { JournalStore.json.encodeToString(DaySignals.serializer(), it) + "\n" })
            _days.value = _days.value + ds.associateBy { it.date }
        }
    }

    fun exportText(): String = if (::file.isInitialized && file.exists()) file.readText() else ""
}

/**
 * Fills in [DaySignals] for recent complete days. A day is complete once its late-night
 * window has closed (03:00 the next morning). Days that are missing wearable data are
 * retried for a few days, because Fitbit can sync late.
 */
object SignalCollector {

    suspend fun collect(ctx: Context, now: LocalDateTime = LocalDateTime.now()) = withContext(Dispatchers.IO) {
        SignalStore.init(ctx)
        val have = SignalStore.days.value
        val today = now.toLocalDate()
        val lastComplete = if (now.hour >= LATE_END) today.minusDays(1) else today.minusDays(2)
        val lookback = if (have.isEmpty()) FIRST_RUN_DAYS else LOOKBACK_DAYS
        val targets = (0 until lookback).map { lastComplete.minusDays(it.toLong()) }.filter { d ->
            val prev = have[d.toString()] ?: return@filter true
            // Re-read recent days whose wearable data hadn't synced yet.
            d >= lastComplete.minusDays(RETRY_DAYS) && (prev.steps == null || prev.wake == null || prev.screenMin == null)
        }
        if (targets.isEmpty()) return@withContext

        val usage = UsageRepository(ctx)
        val hasUsage = usage.hasUsageAccess()
        val zone = ZoneId.systemDefault()
        val source = SenseSettings.source(ctx)
        val granted = if (Sleep.available(ctx)) runCatching { Sleep.granted(ctx) }.getOrDefault(emptySet()) else emptySet()
        val from = targets.min().atStartOfDay(zone).toInstant()
        val to = now.atZone(zone).toInstant()
        val wakes = if (Sleep.READ in granted) runCatching { Sleep.wakeTimes(ctx, source, from, to) }.getOrNull() else null
        val moves = if (granted.isNotEmpty()) runCatching { Movement.days(ctx, source, from, to) }.getOrNull() else null

        val out = targets.map { d ->
            var u = if (hasUsage) runCatching { usage.collectForDay(d, LATE_START, LATE_END) }.getOrNull() else null
            var k = if (hasUsage) Unlocks.day(ctx, d) else null
            // Android keeps detailed app events for only about nine days. Past that a day reads
            // as zero use — which would drag your baseline to nothing — so it's unknown instead.
            if (k != null && k.count == 0 && (u?.totalMinutes ?: 0L) == 0L) { u = null; k = null }
            val m = MediaLog.read(ctx, d)
            val mv = moves?.get(d)
            DaySignals(
                date = d.toString(),
                screenMin = u?.totalMinutes,
                lateNightMin = u?.lateNightMinutes,
                unlocks = k?.count,
                firstUnlock = k?.first?.toLocalTime()?.withNano(0)?.toString(),
                lastUnlock = k?.last?.toLocalTime()?.withNano(0)?.toString(),
                wake = wakes?.get(d)?.toLocalTime()?.withNano(0)?.toString(),
                // No wearable data for the day is "unknown", not zero steps.
                steps = mv?.steps,
                longestWalkMin = mv?.longestWalkMin,
                tracks = m?.started,
                skips = m?.skipped,
                longForm = m?.longForm,
                t = nowStamp(),
            )
        }
        SignalStore.put(out)
    }

    const val LATE_START = 23
    const val LATE_END = 3
    private const val FIRST_RUN_DAYS = 14
    private const val LOOKBACK_DAYS = 7
    private const val RETRY_DAYS = 3L
}

/**
 * The original fugazi radar, now a check-in: late-night phone use up on consecutive nights
 * AND total screen time up, both against your own recent median. Fires at most once a week.
 */
object Radar {
    const val ID = "radar"

    /** Stands in for a habit so the radar can use the check-in card and notification. */
    fun habit(message: String) = Habit(id = ID, name = "Late nights", why = message, created = "2026-01-01")

    suspend fun message(ctx: Context) = SetupStore(ctx).read().message

    /** A new radar trigger, if the numbers call for one. */
    fun evaluate(signals: Collection<DaySignals>, events: List<Event>, today: LocalDate): Event? {
        val history = signals.mapNotNull { s ->
            val late = s.lateNightMin ?: return@mapNotNull null
            val total = s.screenMin ?: return@mapNotNull null
            DailyUsage(LocalDate.parse(s.date).toEpochDay(), total, late, 0)
        }
        val lastTrip = events.filter { it.habit == ID && it.type == EventType.TRIGGER }
            .mapNotNull { it.date?.let(LocalDate::parse)?.toEpochDay() }.maxOrNull() ?: -1L
        val r = TripEvaluator.evaluate(history, lastTrip)
        if (r !is TripEvaluator.Result.Trip) return null
        return Event(t = nowStamp(), habit = ID, type = EventType.TRIGGER, date = today.toString(), rung = 0, text = r.reason)
    }
}
