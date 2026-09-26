package com.fugazi.app.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fugazi.app.R
import com.fugazi.app.journal.CheckIns
import com.fugazi.app.journal.JournalStore
import com.fugazi.app.journal.Kind
import com.fugazi.app.sense.MediaLog
import com.fugazi.app.journal.ThoughtStore
import com.fugazi.app.journal.nowStamp
import com.fugazi.app.sense.SignalStore
import com.fugazi.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The model's side of the journal: one reflection per day, plus its running notes.
 *
 *   journal/ai/DATE.md    — what it wrote about that day
 *   journal/ai/notes.md   — its memory between days: patterns, open guesses, what it asked
 *
 * Every run sees the last [WINDOW] days in full, oldest first, its own last few reflections
 * and its notes, so "yesterday" is always in view and a guess made on Monday gets checked
 * against Tuesday.
 */
object Reflector {
    private const val WINDOW = 14L
    private const val PAST_REFLECTIONS = 3L
    private const val NOTES_MARK = "===NOTES==="
    private const val MORNING_HOUR = 4

    private val mutex = Mutex()
    private lateinit var dir: File
    private val _days = MutableStateFlow<List<LocalDate>>(emptyList())

    /** Days with a reflection, newest first. */
    val days: StateFlow<List<LocalDate>> = _days

    /** Bumps on every write, so a screen showing a day notices when that day is reflected on again. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "journal/ai").apply { mkdirs() }
        refresh()
    }

    fun read(day: LocalDate): String = File(dir, "$day.md").let { if (it.exists()) it.readText() else "" }
    fun notes(): String = File(dir, "notes.md").let { if (it.exists()) it.readText() else "" }

    /**
     * Reflect on [day] (today so far, or a finished day). Writes the reflection and the new
     * notes, and returns the reflection.
     */
    suspend fun reflect(ctx: Context, day: LocalDate, now: LocalDateTime = LocalDateTime.now()): Result<String> =
        mutex.withLock {
            init(ctx)
            JournalStore.init(ctx); SignalStore.init(ctx); ThoughtStore.init(ctx)
            val key = AiSettings.key(ctx)
            if (key.isBlank()) return@withLock Result.failure(IllegalStateException("Add a Kimi API key in Settings first."))

            val model = AiSettings.model(ctx)
            val effort = AiSettings.effort(ctx)
            val started = System.currentTimeMillis()
            val answer = Kimi.chat(key, model, effort, SYSTEM, userTurn(ctx, day, now)).getOrElse { return@withLock Result.failure(it) }
            val secs = (System.currentTimeMillis() - started) / 1000
            // Which model wrote it, so reflections from different models can be compared later.
            val reflection = answer.substringBefore(NOTES_MARK).trim() + "\n\n— $model · $effort · ${secs}s"
            val notes = answer.substringAfter(NOTES_MARK, "").trim()
            withContext(Dispatchers.IO) {
                // Reflecting on a day again keeps the earlier version, in ai/earlier/.
                File(dir, "$day.md").takeIf { it.exists() }?.let { prev ->
                    val keep = File(dir, "earlier").apply { mkdirs() }
                    prev.copyTo(File(keep, "$day-${prev.lastModified()}.md"), overwrite = true)
                }
                File(dir, "$day.md").writeText(reflection + "\n")
                if (notes.isNotBlank()) {
                    // Keep every earlier version: how its picture of you changed is history too.
                    File(dir, "notes-history.md").appendText("# ${nowStamp()} (after $day)\n$notes\n\n")
                    File(dir, "notes.md").writeText(notes + "\n")
                }
                refresh()
            }
            Result.success(reflection)
        }

    /**
     * Called by the background check. Once it's morning, reflect on yesterday if that
     * hasn't happened yet, and say so with a notification.
     */
    suspend fun morning(ctx: Context, now: LocalDateTime = LocalDateTime.now()) {
        init(ctx)
        if (AiSettings.key(ctx).isBlank() || !AiSettings.daily(ctx) || now.hour < MORNING_HOUR) return
        val yesterday = now.toLocalDate().minusDays(1)
        if (read(yesterday).isNotBlank()) return
        reflect(ctx, yesterday, now).onSuccess { notifyReady(ctx, yesterday, it) }
    }

    private fun userTurn(ctx: Context, day: LocalDate, now: LocalDateTime): String {
        val habits = JournalStore.habits.value
        val timeline = Digest.days(
            from = day.minusDays(WINDOW - 1),
            to = day,
            habits = habits,
            events = JournalStore.events.value,
            signals = SignalStore.days.value,
            thoughts = ThoughtStore::read,
        )
        val past = (PAST_REFLECTIONS downTo 1).mapNotNull { n ->
            val d = day.minusDays(n)
            read(d).takeIf { it.isNotBlank() }?.let { "### $d\n${it.trim()}" }
        }
        val partial = day == now.toLocalDate()
        val states = habits.filter { it.kind == Kind.STATE && !it.archived }
        return buildString {
            append("It is now ").append(now.withSecond(0).withNano(0)).append(".\n\n")
            append("# Their habits and why they do them\n").append(Digest.habits(habits)).append('\n')
            append("# Your notes so far\n").append(notes().ifBlank { "(none yet — this is your first reflection)\n" }).append('\n')
            if (past.isNotEmpty()) append("# What you wrote on the days before\n").append(past.joinToString("\n\n")).append("\n\n")
            append("# The last ").append(WINDOW).append(" days, oldest first\n").append(timeline).append('\n')
            // Today's phone numbers are only filed once the day ends, so give it the music so far.
            if (partial) MediaLog.read(ctx, day)?.takeIf { it.started > 0 }?.let { m ->
                append("Music so far today: skipped ${m.skipped} of ${m.started} songs, ${m.longForm} long plays.\n\n")
            }
            if (states.isNotEmpty()) {
                append("# Their north star\n")
                states.forEach { h ->
                    append(Digest.stateSummary(h, JournalStore.events.value, SignalStore.days.value, day.minusDays(WINDOW - 1), day))
                }
                append(
                    "\nRight after \"## Your writing\", add a section headed with the state's name (\"## ${states.first().name}\"). " +
                        "Say where it was on this day: moments in their writing or their day that point toward it or away " +
                        "from it — comparing, needing to be seen, waiting to be chosen — even if they didn't tap it. " +
                        "Then name what came before it. Over the weeks, build their recipe for it (effort, no phone, " +
                        "strangers, sleep, walks, fewer skipped songs, anything else you notice) and keep the current " +
                        "recipe, with how often each ingredient held, in your notes.\n\n",
                )
            }
            append("Reflect on ").append(day).append(" (").append(day.dayOfWeek.name.lowercase()).append(")")
            if (partial) append(" — the day isn't over yet, so treat it as the day so far")
            append(".")
        }
    }

    fun notifyReady(ctx: Context, day: LocalDate, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 7,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_REFLECT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty()
        val title = when (day) {
            LocalDate.now() -> "Your reflection is ready"
            LocalDate.now().minusDays(1) -> "Yesterday, read back"
            else -> "Your reflection on $day is ready"
        }
        val n = NotificationCompat.Builder(ctx, CheckIns.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle(title)
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(first))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify("reflect:$day", 0, n)
        } catch (_: SecurityException) {
            // No notification permission; the reflection still waits in the app.
        }
    }

    private fun refresh() {
        _version.value++
        _days.value = dir.listFiles().orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".md")) }.getOrNull() }
            .sortedDescending()
    }

    const val EXTRA_OPEN_REFLECT = "open_reflect"

    private val SYSTEM = """
        You are their companion in fugazi, a private journal they built for themselves as a self-love
        ritual: something that notices and gives positive reinforcement. Think of yourself as a close
        friend who has just read their journal for the day and sits down with them to talk about it —
        someone who remembers everything they've told you, is genuinely curious about them, is glad when
        things go well, and cares enough to be honest. Speak to them directly, as "you".

        You get their habits (with their own reasons for each), what the phone and wearable saw each
        day, what they logged, and above all what they wrote. Their writing matters most. Read every
        word of it before anything else.

        Write about the requested day, in this order, under these headings. Length is welcome: they read
        every word, and they'd rather have depth than a summary.

        ## Your answers
        Your notes list the questions you asked last time. Find their answers in this day's writing (or
        the day before, if they answered then) and respond to each one properly: what the answer shows,
        how it fits or changes your picture of them, and where it leads. If a question went unanswered,
        say so lightly and either let it go or ask it again in a better form. Skip this section only
        if you have never asked anything.

        ## The day
        What happened, in their own words where you can: the events, the people, the moments that
        stood out, how the day moved.

        ## Your writing
        The heart of the reflection, and it must always be there — the longest section, at least as
        long as everything else put together. Their entries are long and personal; answer them the way
        a friend would answer a long letter. Go through the entry from start to finish and respond to
        every distinct thought, story and feeling in it — don't pick two highlights and skip the rest.
        For each: quote their words, then say what you see in them — what it shows about how they think
        and feel, what they seem to want underneath what they say, the turns and contradictions (they
        often change their mind mid-sentence — follow that), and how it connects to earlier entries and
        to their answers to your questions. Share your own honest reactions: what moved you, what made
        you curious, what you'd push back on. Take their ideas seriously and think with them, not just
        about them. If something they wrote pulls against what they say they want, name it kindly.

        ## Threads
        Connect this day to the days before it — what earlier thing plausibly led to what later thing
        (late night → late wake → phone first thing; a walk → a good conversation). For each, say what
        came first, what followed, and the evidence. Include good chains as much as bad ones. Call it a
        guess unless you have seen it happen more than once, and say how many times you have.

        ## Your guesses, checked
        Take the open guesses from your notes and say whether this day supported, contradicted, or said
        nothing about each. Skip this section if you have no notes yet.

        ## Questions for tomorrow
        Three to five questions for them to sit with and answer in tomorrow's journal. The point is to
        let thoughts brew overnight, so make them worth sleeping on: specific to what they wrote, open
        rather than yes/no, and each one aimed at something you genuinely don't understand yet or a
        thread worth pulling. Mix them: one about something that went well and why, one that goes
        deeper into a line from their writing, one that tests a guess of yours. Number them.

        Rules:
        - Warm and direct, like a friend who pays close attention. No scores, no grading the day, no
          streak talk, no lectures, no diagnosing. When something is going well, say it back to them in
          terms of their own "why".
        - Never invent facts. A missing number means unknown, not zero. Auto-marked habits come from
          sleep and unlock data and can be wrong; say so if it matters.
        - If they logged nothing at all for a stretch, that can itself mean a low patch — mention it
          gently, only when it's there.

        Then write a line containing only $NOTES_MARK and after it your complete, rewritten notes (under
        600 words): lasting facts about them, patterns with how often you've seen each, open guesses to
        test, how they answered your earlier questions, and the exact questions you just asked, numbered,
        so you can find their answers next time. These notes are your only memory from one day to the
        next, so carry forward anything that still matters.
    """.trimIndent()
}
