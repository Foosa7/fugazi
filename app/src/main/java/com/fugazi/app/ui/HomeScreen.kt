package com.fugazi.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fugazi.app.journal.Event
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.HabitState
import com.fugazi.app.journal.Kind
import com.fugazi.app.journal.describe
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Things the home screen needs to flag about the app itself, not about you. */
data class Health(
    val lastCheckMs: Long,
    val batteryRestricted: Boolean,
    val notificationsOff: Boolean,
)

@Composable
fun HomeScreen(
    states: List<HabitState>,
    pending: List<Pair<Habit, Event>>,
    today: LocalDate,
    health: Health,
    onToggle: (Habit, LocalDate) -> Unit,
    onSkip: (Habit, LocalDate, String) -> Unit,
    onAnswer: (Event, String) -> Unit,
    onDismiss: (Event) -> Unit,
    onOpen: (Habit?) -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onFixBattery: () -> Unit,
    onFixNotifications: () -> Unit,
) {
    var skipping by remember { mutableStateOf<Pair<Habit, LocalDate>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("fugazi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("⚙") }
                TextButton(onClick = onExport) { Text("Export") }
                Button(onClick = { onOpen(null) }) { Text("+ Habit") }
            }
            HealthLine(health, onFixBattery, onFixNotifications)
        }

        items(pending, key = { (_, e) -> "${e.habit}:${e.rung}:${e.t}" }) { (h, e) ->
            CheckInCard(h, e, onAnswer = { onAnswer(e, it) }, onDismiss = { onDismiss(e) })
        }

        if (states.isEmpty()) {
            item {
                Text(
                    "No habits yet. Add one — and write down why it matters.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { DayHeader(today) }

        items(states, key = { it.habit.id }) { s ->
            HabitRow(
                s,
                today,
                onToggle = { d -> onToggle(s.habit, d) },
                onLongPress = { d -> skipping = s.habit to d },
                onOpen = { onOpen(s.habit) },
            )
        }
    }

    skipping?.let { (h, d) ->
        SkipDialog(h, d, onDone = { reason ->
            if (reason != null) onSkip(h, d, reason)
            skipping = null
        })
    }
}

@Composable
private fun HealthLine(h: Health, onFixBattery: () -> Unit, onFixNotifications: () -> Unit) {
    val ago = if (h.lastCheckMs == 0L) null else (System.currentTimeMillis() - h.lastCheckMs) / 3_600_000
    val stale = ago == null || ago >= 12
    val line = when {
        ago == null -> "Background check hasn't run yet."
        ago < 1 -> "Background check ran under an hour ago."
        else -> "Background check ran ${ago}h ago."
    }
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = if (stale && ago != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (h.batteryRestricted) {
        FixCard(
            "Android may put fugazi to sleep, and check-ins would stop without you noticing.",
            "Allow in background",
            onFixBattery,
        )
    }
    if (h.notificationsOff) {
        FixCard("Notifications are off, so check-ins only show up here.", "Turn on", onFixNotifications)
    }
}

@Composable
private fun FixCard(text: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInCard(h: Habit, e: Event, onAnswer: (String) -> Unit, onDismiss: () -> Unit) {
    val p = describe(h, e)
    var text by remember(e.t) { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (p.win) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            if (p.body.isNotBlank()) Text(p.body, style = MaterialTheme.typography.bodyMedium)
            if (p.ask.isNotBlank()) Text(p.ask, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (p.likely.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.likely.forEach { c -> AssistChip(onClick = { onAnswer(c) }, label = { Text(c) }) }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (p.win) "What's working…" else "In your own words…") },
            )
            Row {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onAnswer(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") }
            }
        }
    }
}

@Composable
private fun DayHeader(today: LocalDate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        week(today).forEach { d ->
            Text(
                d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                modifier = Modifier.width(DOT_SLOT),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (d == today) FontWeight.Bold else null,
                color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitRow(
    s: HabitState,
    today: LocalDate,
    onToggle: (LocalDate) -> Unit,
    onLongPress: (LocalDate) -> Unit,
    onOpen: () -> Unit,
) {
    val h = s.habit
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 4.dp),
            ) {
                Text(h.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    status(s, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            week(today).forEach { d ->
                val marked = if (h.kind == Kind.DO) d in s.done else d in s.slips
                val skipped = d in s.skips
                val future = d.isAfter(today)
                Box(
                    Modifier
                        .width(DOT_SLOT)
                        .combinedClickable(
                            enabled = !future,
                            onClick = { onToggle(d) },
                            onLongClick = { onLongPress(d) },
                        )
                        .padding(vertical = 8.dp)
                        .alpha(if (future) 0.3f else 1f),
                    contentAlignment = Alignment.Center,
                ) { Dot(h.kind, marked, skipped) }
            }
        }
        StrengthBar(s.strength.toFloat())
    }
}

/** Loop-style habit strength. A plain bar: Material's progress indicator reads as full at 0%. */
@Composable
private fun StrengthBar(value: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

@Composable
private fun Dot(kind: Kind, marked: Boolean, skipped: Boolean) {
    val c = MaterialTheme.colorScheme
    val mod = Modifier.size(22.dp)
    when {
        skipped -> Box(mod.border(2.dp, c.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
            Text("–", style = MaterialTheme.typography.labelSmall, color = c.outline)
        }
        marked && kind == Kind.DO -> Box(mod.background(c.primary, CircleShape))
        marked -> Box(mod.background(c.error, CircleShape))
        else -> Box(mod.border(2.dp, c.outlineVariant, CircleShape))
    }
}

@Composable
private fun SkipDialog(h: Habit, d: LocalDate, onDone: (String?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Skip ${h.name} on $d?") },
        text = {
            Column {
                Text("A skipped day doesn't count toward the ladder.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(reason, { reason = it }, placeholder = { Text("Why? (sick, travelling…)") })
            }
        },
        confirmButton = { TextButton(onClick = { onDone(reason.trim()) }) { Text("Skip") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

private fun status(s: HabitState, today: LocalDate): String {
    val h = s.habit
    val rung = s.rung?.let { h.ladder[it].meaning }?.takeIf { it.isNotBlank() }
    val base = when (h.kind) {
        Kind.DO -> when {
            today in s.done -> "Done today"
            s.done.isEmpty() -> "Not done yet"
            s.lapse == 1 -> "Last done yesterday"
            else -> "${s.lapse} days since"
        }
        Kind.AVOID -> if (s.lapse == 1) "1 day clean" else "${s.lapse} days clean"
    }
    return listOfNotNull(base, rung).joinToString(" · ")
}

/** The calendar week containing [today], Sunday through Saturday. */
private fun week(today: LocalDate): List<LocalDate> {
    val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
    return (0L..6L).map { sunday.plusDays(it) }
}

private val DOT_SLOT = 34.dp
