package com.fugazi.app.journal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The journal is two append-only JSONL files in app storage:
 *
 *   journal/habits.jsonl  — every version of every habit
 *   journal/events.jsonl  — every done / slip / skip / trigger / answer
 *
 * Nothing is ever rewritten in place, so the files are the full history and can be handed
 * to a model as-is. The in-memory flows are a cache of them for the UI.
 */
object JournalStore {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val mutex = Mutex()
    private lateinit var dir: File
    private val habitsFile get() = File(dir, "habits.jsonl")
    private val eventsFile get() = File(dir, "events.jsonl")

    private val _habits = MutableStateFlow<List<Habit>>(emptyList())
    private val _events = MutableStateFlow<List<Event>>(emptyList())

    /** Latest version of each habit, archived included, in creation order. */
    val habits: StateFlow<List<Habit>> = _habits
    val events: StateFlow<List<Event>> = _events

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "journal").apply { mkdirs() }
        if (!habitsFile.exists()) seed()
        load()
    }

    suspend fun saveHabit(h: Habit) = write {
        val prev = _habits.value.firstOrNull { it.id == h.id }
        val next = h.copy(v = (prev?.v ?: 0) + 1, t = nowStamp())
        habitsFile.appendText(json.encodeToString(Habit.serializer(), next) + "\n")
        _habits.value = if (prev == null) _habits.value + next
        else _habits.value.map { if (it.id == h.id) next else it }
    }

    suspend fun append(e: Event) = appendAll(listOf(e))

    suspend fun appendAll(es: List<Event>) {
        if (es.isEmpty()) return
        write {
            eventsFile.appendText(es.joinToString("") { json.encodeToString(Event.serializer(), it) + "\n" })
            _events.value = _events.value + es
        }
    }

    /**
     * Evaluate every habit and log any new triggers. Returns the ones just fired so the
     * caller can notify. Safe to call often — each rung fires once per lapse.
     */
    suspend fun check(today: LocalDate = LocalDate.now()): List<Event> {
        val evs = _events.value
        val fired = _habits.value.mapNotNull { h -> nextTrigger(reduce(h, evs, today), evs, today) }
        appendAll(fired)
        return fired
    }

    /** Both files, verbatim, for sharing with a model or backing up. */
    fun exportText(): String = buildString {
        append("# fugazi journal export ").append(nowStamp()).append('\n')
        append("## habits.jsonl\n").append(habitsFile.readTextOrEmpty())
        append("## events.jsonl\n").append(eventsFile.readTextOrEmpty())
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun load() {
        val latest = LinkedHashMap<String, Habit>()
        habitsFile.readLinesOrEmpty().forEach { line ->
            runCatching { json.decodeFromString(Habit.serializer(), line) }.getOrNull()
                ?.let { latest[it.id] = it }
        }
        _habits.value = latest.values.toList()
        _events.value = eventsFile.readLinesOrEmpty().mapNotNull { line ->
            runCatching { json.decodeFromString(Event.serializer(), line) }.getOrNull()
        }
    }

    private suspend fun write(block: () -> Unit) = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** One worked example so the meta fields make sense on first open. Edit or archive it. */
    private fun seed() {
        val h = Habit(
            id = "vitb",
            v = 1,
            name = "Vitamin B",
            `when` = "morning",
            why = "Taking it makes me feel like I'm taking care of myself, and I feel good.",
            goal = "Steady energy through the day.",
            ladder = listOf(
                Rung(days = 3, meaning = "Fine."),
                Rung(
                    days = 7,
                    meaning = "Concerning.",
                    ask = "A week without it. Are mornings busy?",
                    likely = listOf("Busy mornings", "Ran out", "Forgot", "Didn't feel like it"),
                ),
                Rung(
                    days = 10,
                    meaning = "Something in the routine has changed.",
                    ask = "What's different about my mornings right now?",
                ),
            ),
            created = LocalDate.now().toString(),
            t = nowStamp(),
        )
        habitsFile.writeText(json.encodeToString(Habit.serializer(), h) + "\n")
    }
}

private fun File.readLinesOrEmpty(): List<String> =
    if (exists()) readLines().filter { it.isNotBlank() } else emptyList()

private fun File.readTextOrEmpty(): String = if (exists()) readText() else ""
