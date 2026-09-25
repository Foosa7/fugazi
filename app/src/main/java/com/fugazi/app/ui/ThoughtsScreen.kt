package com.fugazi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fugazi.app.journal.ThoughtStore
import com.fugazi.app.sense.DaySignals
import com.fugazi.app.sense.SignalStore
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A blank page for today, and every earlier day underneath. It saves as you type; there's
 * no button to press and nothing to fill in.
 */
@Composable
fun ThoughtsScreen() {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    var text by remember(day) { mutableStateOf(ThoughtStore.read(day)) }
    var saved by remember(day) { mutableStateOf(text) }
    val days by ThoughtStore.days.collectAsState()
    val signals by SignalStore.days.collectAsState()

    // Moving to another day cancels the pending save below, so flush it first.
    fun open(d: LocalDate) {
        if (text != saved) ThoughtStore.writeNow(day, text)
        day = d
    }

    // Same when leaving the tab altogether.
    val latest by rememberUpdatedState(Triple(day, text, saved))
    DisposableEffect(Unit) {
        onDispose { latest.let { (d, t, s) -> if (t != s) ThoughtStore.writeNow(d, t) } }
    }

    // Save a moment after typing stops, rather than on every keystroke.
    LaunchedEffect(day, text) {
        if (text == saved) return@LaunchedEffect
        delay(800)
        ThoughtStore.write(day, text)
        saved = text
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (day == today) "Today" else day.format(LONG),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (day != today) TextButton(onClick = { open(today) }) { Text("Back to today") }
        }
        signals[day.toString()]?.let { SignalLine(it) }

        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            placeholder = { Text("What's on your mind?") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        val earlier = days.filter { it != day }
        if (earlier.isNotEmpty()) {
            HorizontalDivider()
            Text("Earlier", style = MaterialTheme.typography.titleMedium)
            earlier.forEach { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(d) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        if (d == today) "Today" else d.format(LONG),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        ThoughtStore.read(d).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

/** What the phone saw that day, in one quiet line — context for rereading, not a score. */
@Composable
private fun SignalLine(s: DaySignals) {
    val parts = listOfNotNull(
        s.wake?.let { "woke ${it.take(5)}" },
        s.lateNightMin?.let { "$it min phone after 23:00" },
        s.steps?.let { "%,d steps".format(it) },
        s.longestWalkMin?.takeIf { it > 0 }?.let { "walked $it min" },
        s.tracks?.takeIf { it > 0 }?.let { "skipped ${s.skips ?: 0}/$it songs" },
    )
    if (parts.isEmpty()) return
    Text(
        "${LocalDate.parse(s.date).format(SHORT)}: " + parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM")
