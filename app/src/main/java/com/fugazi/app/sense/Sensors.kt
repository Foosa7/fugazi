package com.fugazi.app.sense

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.records.metadata.DataOrigin
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Where sleep comes from. Each wearable writes to Health Connect under its own app. */
enum class SleepSource(val label: String, val packageName: String?) {
    FITBIT("Fitbit", "com.fitbit.FitbitMobile"),
    SAMSUNG("Galaxy Watch (Samsung Health)", "com.sec.android.app.shealth"),
    ANY("Any app", null),
}

/** The one setting auto-marking needs. Kept out of the journal: it's about the phone, not you. */
object SenseSettings {
    private const val PREFS = "sense"
    private const val KEY_SOURCE = "sleep_source"

    fun source(ctx: Context): SleepSource =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, null)
            ?.let { runCatching { SleepSource.valueOf(it) }.getOrNull() } ?: SleepSource.FITBIT

    fun setSource(ctx: Context, s: SleepSource) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, s.name).apply()
    }
}

object Sleep {
    val READ = HealthPermission.getReadPermission(SleepSessionRecord::class)
    const val READ_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    /** Background reads are a separate grant, and only exist on newer Health Connect. */
    fun backgroundSupported(ctx: Context): Boolean = available(ctx) &&
        client(ctx).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val READ_EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    fun wantedPermissions(ctx: Context): Set<String> =
        setOf(READ, READ_STEPS, READ_EXERCISE) + if (backgroundSupported(ctx)) setOf(READ_BACKGROUND) else emptySet()

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet() else client(ctx).permissionController.getGrantedPermissions()

    /**
     * Wake time per local date: the end of the longest sleep ending that day, so a nap
     * doesn't count as waking up. Sessions under [MIN_NIGHT] are ignored altogether.
     */
    suspend fun wakeTimes(ctx: Context, source: SleepSource, from: Instant, to: Instant): Map<LocalDate, LocalDateTime> {
        val origin = source.packageName?.let { setOf(DataOrigin(it)) } ?: emptySet()
        val zone = ZoneId.systemDefault()
        return readAll(client(ctx), SleepSessionRecord::class, TimeRangeFilter.between(from, to), origin)
            .filter { Duration.between(it.startTime, it.endTime) >= MIN_NIGHT }
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, rs) ->
                rs.maxBy { Duration.between(it.startTime, it.endTime) }.endTime.atZone(zone).toLocalDateTime()
            }
    }

    private val MIN_NIGHT: Duration = Duration.ofHours(3)
}

/** Every page of a read — a few weeks of per-minute steps is more than one page. */
suspend fun <T : Record> readAll(
    c: HealthConnectClient,
    type: KClass<T>,
    range: TimeRangeFilter,
    origin: Set<DataOrigin>,
): List<T> {
    val out = mutableListOf<T>()
    var token: String? = null
    do {
        val page = c.readRecords(ReadRecordsRequest(type, range, origin, pageSize = 5000, pageToken = token))
        out += page.records
        token = page.pageToken
    } while (token != null)
    return out
}

/** One day of movement: total steps, and the longest stretch of continuous walking. */
data class DayMovement(val steps: Long, val longestWalkMin: Int)

object Movement {
    /**
     * Per local date. A walk is either a walking/hiking session the wearable recorded, or a
     * run of step records at walking pace ([WALK_PACE] steps/min or more) with gaps of at most
     * [MAX_GAP]. Step records longer than [MAX_BUCKET] are too coarse to say whether the
     * steps were one walk or a day of pottering, so they count toward steps but not walks.
     */
    suspend fun days(ctx: Context, source: SleepSource, from: Instant, to: Instant): Map<LocalDate, DayMovement> {
        val c = Sleep.client(ctx)
        val origin = source.packageName?.let { setOf(DataOrigin(it)) } ?: emptySet()
        val zone = ZoneId.systemDefault()
        val granted = Sleep.granted(ctx)

        val steps = if (Sleep.READ_STEPS in granted) {
            readAll(c, StepsRecord::class, TimeRangeFilter.between(from, to), origin)
        } else emptyList()
        val sessions = if (Sleep.READ_EXERCISE in granted) {
            readAll(c, ExerciseSessionRecord::class, TimeRangeFilter.between(from, to), origin)
                .filter { it.exerciseType in WALKING }
        } else emptyList()

        val runs = walkingRuns(steps.map { StepSpan(it.startTime, it.endTime, it.count) })
        val dates = (steps.map { it.startTime } + sessions.map { it.startTime }).map { it.atZone(zone).toLocalDate() }.toSet()
        return dates.associateWith { d ->
            val onDay = { t: Instant -> t.atZone(zone).toLocalDate() == d }
            val longest = (runs.filter { onDay(it.first) }.map { Duration.between(it.first, it.second) } +
                sessions.filter { onDay(it.startTime) }.map { Duration.between(it.startTime, it.endTime) })
                .maxOfOrNull { it.toMinutes().toInt() } ?: 0
            DayMovement(steps.filter { onDay(it.startTime) }.sumOf { it.count }, longest)
        }
    }

    private val WALKING = setOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING)
}

data class StepSpan(val start: Instant, val end: Instant, val count: Long)

/** Continuous walking stretches as (start, end), from step records. Pure, for tests. */
fun walkingRuns(spans: List<StepSpan>): List<Pair<Instant, Instant>> {
    val walking = spans.filter {
        val d = Duration.between(it.start, it.end)
        !d.isZero && d <= MAX_BUCKET && it.count * 60.0 / d.seconds >= WALK_PACE
    }.sortedBy { it.start }
    val out = mutableListOf<Pair<Instant, Instant>>()
    for (s in walking) {
        val last = out.lastOrNull()
        if (last != null && !s.start.isAfter(last.second.plus(MAX_GAP))) {
            out[out.lastIndex] = last.first to maxOf(last.second, s.end)
        } else out += s.start to s.end
    }
    return out
}

const val WALK_PACE = 60.0
val MAX_GAP: Duration = Duration.ofMinutes(2)
val MAX_BUCKET: Duration = Duration.ofMinutes(20)

object Unlocks {
    data class Day(val count: Int, val first: LocalDateTime?, val last: LocalDateTime?)

    /** Unlocks during one calendar day. */
    fun day(ctx: Context, date: LocalDate): Day {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Day(0, null, null)
        val zone = ZoneId.systemDefault()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = try {
            usm.queryEvents(
                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        } catch (_: SecurityException) {
            return Day(0, null, null)
        }
        var n = 0
        var first: Long? = null
        var last: Long? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                n++
                if (first == null) first = e.timeStamp
                last = e.timeStamp
            }
        }
        fun t(ms: Long?) = ms?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        return Day(n, t(first), t(last))
    }

    /**
     * First time the lock screen was dismissed in [from, to). A dismissed alarm or a glance at
     * the lock screen doesn't count — getting past the lock does.
     */
    fun firstAfter(ctx: Context, from: LocalDateTime, to: LocalDateTime): LocalDateTime? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val zone = ZoneId.systemDefault()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = try {
            usm.queryEvents(from.atZone(zone).toInstant().toEpochMilli(), to.atZone(zone).toInstant().toEpochMilli())
        } catch (_: SecurityException) {
            return null
        }
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timeStamp), zone)
            }
        }
        return null
    }
}
