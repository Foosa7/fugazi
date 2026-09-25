package com.fugazi.app.journal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Free writing, one plain-text file per day: journal/thoughts/2026-09-26.md. No structure,
 * no prompts — this is the part that was always working. Plain files so they can be read,
 * grepped, or handed to a model without any conversion.
 */
object ThoughtStore {
    private lateinit var dir: File
    private val _days = MutableStateFlow<List<LocalDate>>(emptyList())

    /** Days that have something written, newest first. */
    val days: StateFlow<List<LocalDate>> = _days

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "journal/thoughts").apply { mkdirs() }
        refresh()
    }

    fun read(day: LocalDate): String = file(day).let { if (it.exists()) it.readText() else "" }

    suspend fun write(day: LocalDate, text: String) = withContext(Dispatchers.IO) { writeNow(day, text) }

    /** Blocking — for flushing on the way out of the screen, where there's no time to launch. */
    fun writeNow(day: LocalDate, text: String) {
        val f = file(day)
        if (text.isBlank()) f.delete() else f.writeText(text)
        refresh()
    }

    fun exportText(): String = buildString {
        _days.value.sorted().forEach { d -> append("### ").append(d).append('\n').append(read(d).trimEnd()).append("\n\n") }
    }

    private fun file(day: LocalDate) = File(dir, "$day.md")

    private fun refresh() {
        _days.value = dir.listFiles().orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".md")) }.getOrNull() }
            .sortedDescending()
    }
}
