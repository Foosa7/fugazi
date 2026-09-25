package com.fugazi.app.journal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fugazi.app.R
import com.fugazi.app.sense.AutoMarker
import com.fugazi.app.sense.Radar
import com.fugazi.app.sense.SignalCollector
import com.fugazi.app.sense.SignalStore
import com.fugazi.app.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The day the ladder is judged against. Before 19:00 today isn't over, so a lapse is
 * counted through yesterday — otherwise "7 days without vitamin B" would fire at 00:10
 * on a day you still had time to take it.
 */
fun evalDay(now: LocalDateTime = LocalDateTime.now()): LocalDate =
    if (now.hour >= EVAL_HOUR) now.toLocalDate() else now.toLocalDate().minusDays(1)

const val EVAL_HOUR = 19

/** Runs a few times a day: evaluate every habit, notify for anything that fired. */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val fired = runChecks(applicationContext)
        Heartbeat.beat(applicationContext)
        fired.forEach { (h, e) -> CheckIns.notify(applicationContext, h, e) }
        return Result.success()
    }
}

/**
 * Everything a check does, in order: record the phone's signals, auto-mark from the
 * wearable, then evaluate ladders and the late-night radar. Auto-marking goes before the
 * ladders so a habit marked from last night doesn't also trip. Returns what just fired.
 */
suspend fun runChecks(ctx: Context): List<Pair<Habit, Event>> {
    JournalStore.init(ctx)
    runCatching { SignalCollector.collect(ctx) }
    val status = runCatching { AutoMarker.run(ctx) }.getOrNull()
    val today = evalDay()
    val fired = JournalStore.check(today)
    val habits = JournalStore.habits.value.associateBy { it.id }
    val out = fired.mapNotNull { e -> habits[e.habit]?.let { it to e } }.toMutableList()
    Radar.evaluate(SignalStore.days.value.values, JournalStore.events.value, LocalDate.now())?.let { e ->
        JournalStore.append(e)
        out += Radar.habit(Radar.message(ctx)) to e
    }
    lastStatus = status
    return out
}

/** What the last auto-mark pass reported, for the settings screen. */
@Volatile var lastStatus: AutoMarker.Status? = null

/**
 * When the background check last ran. Shown on the home screen: the old fugazi died
 * silently for three months because nothing made a dead check look different from a
 * quiet one.
 */
object Heartbeat {
    private const val PREFS = "heartbeat"
    private const val KEY = "last_check_ms"

    fun beat(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY, System.currentTimeMillis()).apply()
    }

    fun last(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
}

object CheckIns {
    private const val CHANNEL = "checkins"
    private const val WORK = "fugazi_checkins"
    private const val OLD_RADAR_WORK = "fugazi_daily_radar"
    const val EXTRA_OPEN_CHECKINS = "open_checkins"

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Check-ins", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A habit reached a rung of its ladder, or is going well." },
        )
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        // v1's screen-time radar is retired; its code is kept for later passive signals.
        wm.cancelUniqueWork(OLD_RADAR_WORK)
        wm.enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(4, TimeUnit.HOURS).build(),
        )
    }

    fun notify(ctx: Context, habit: Habit, trigger: Event) {
        val p = describe(habit, trigger)
        val open = PendingIntent.getActivity(
            ctx,
            trigger.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_CHECKINS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(p.title)
            .setContentText(p.ask)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(p.body, p.ask).filter { it.isNotBlank() }.joinToString("\n\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify("${habit.id}:${trigger.rung}", 0, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the check-in still waits in the app.
        }
    }
}

/** How a trigger reads, in the notification and on the home screen. */
data class Prompt(val title: String, val body: String, val ask: String, val likely: List<String>, val win: Boolean)

fun describe(h: Habit, e: Event): Prompt {
    if (h.id == Radar.ID) {
        return Prompt(
            title = "Late nights are creeping up",
            body = listOfNotNull(e.text, h.why.takeIf { it.isNotBlank() }).joinToString("\n\n"),
            ask = "What's keeping me up?",
            likely = listOf("Phone in bed", "Couldn't sleep", "Worked late", "Out with people"),
            win = false,
        )
    }
    val rung = e.rung?.takeIf { it >= 0 }?.let { h.ladder.getOrNull(it) }
    if (rung == null) {
        val what = when (h.kind) {
            Kind.DO -> "On pace for the last ${e.days ?: DO_WIN_WINDOW} days."
            Kind.AVOID -> "${e.days ?: 0} days clean."
        }
        return Prompt(
            title = "${h.name} is going well",
            body = listOf(what, h.why.takeIf { it.isNotBlank() }?.let { "Why you do it: $it" }).filterNotNull().joinToString("\n"),
            ask = h.winAsk ?: DEFAULT_WIN_ASK,
            likely = emptyList(),
            win = true,
        )
    }
    val span = when (h.kind) {
        Kind.DO -> "${rung.days}+ days without it"
        Kind.AVOID -> "${rung.slips} in the last ${rung.days} days"
    }
    return Prompt(
        title = "${h.name}: $span",
        body = listOf(rung.meaning, h.why.takeIf { it.isNotBlank() }?.let { "Why it matters: $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString("\n"),
        ask = rung.ask.orEmpty(),
        likely = rung.likely,
        win = false,
    )
}
