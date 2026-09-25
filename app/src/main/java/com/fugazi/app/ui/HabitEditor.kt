package com.fugazi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fugazi.app.journal.AutoRule
import com.fugazi.app.journal.AutoType
import com.fugazi.app.journal.Event
import com.fugazi.app.journal.EventType
import com.fugazi.app.journal.Habit
import com.fugazi.app.journal.Kind
import com.fugazi.app.journal.Rung
import com.fugazi.app.journal.Target
import com.fugazi.app.journal.WIN_RUNG
import java.time.LocalDate

/** Editable copy of a rung; numbers stay text until save so half-typed input is allowed. */
private class RungDraft(r: Rung) {
    var days by mutableStateOf(r.days.toString())
    var slips by mutableStateOf(r.slips.toString())
    var meaning by mutableStateOf(r.meaning)
    var ask by mutableStateOf(r.ask.orEmpty())
    var likely by mutableStateOf(r.likely.joinToString(", "))

    fun build(): Rung? {
        val d = days.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Rung(
            days = d,
            slips = slips.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            meaning = meaning.trim(),
            ask = ask.trim().ifBlank { null },
            likely = likely.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}

@Composable
fun HabitEditor(
    existing: Habit?,
    history: List<Event>,
    newId: () -> String,
    onSave: (Habit) -> Unit,
    onArchive: (Habit) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: Kind.DO) }
    var times by remember { mutableStateOf((existing?.target?.times ?: 1).toString()) }
    var per by remember { mutableStateOf((existing?.target?.days ?: 1).toString()) }
    var whenOf by remember { mutableStateOf(existing?.`when`.orEmpty()) }
    var why by remember { mutableStateOf(existing?.why.orEmpty()) }
    var goal by remember { mutableStateOf(existing?.goal.orEmpty()) }
    var winAsk by remember { mutableStateOf(existing?.winAsk.orEmpty()) }
    var autoType by remember { mutableStateOf(existing?.auto?.type) }
    var autoMinutes by remember { mutableStateOf((existing?.auto?.minutes ?: 30).toString()) }
    val ladder = remember {
        mutableStateListOf<RungDraft>().apply {
            (existing?.ladder ?: defaultLadder()).forEach { add(RungDraft(it)) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.weight(1f))
            Text(if (existing == null) "New habit" else "Edit habit", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Habit") })

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = kind == Kind.DO,
                onClick = { kind = Kind.DO },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Do more") }
            SegmentedButton(
                selected = kind == Kind.AVOID,
                onClick = { kind = Kind.AVOID },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Do less") }
        }

        if (kind == Kind.DO) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(times, { times = it }, "Times", Modifier.width(90.dp))
                Text("per")
                NumberField(per, { per = it }, "Days", Modifier.width(90.dp))
            }
        }

        OutlinedTextField(whenOf, { whenOf = it }, Modifier.fillMaxWidth(), label = { Text("When (morning, after dinner…)") })
        OutlinedTextField(
            why, { why = it }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("Why — what it does for you") },
        )
        OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("Goal") })

        if (kind == Kind.DO) {
            HorizontalDivider()
            Text("Mark it for me", style = MaterialTheme.typography.titleMedium)
            Text(
                "Uses your watch's sleep and steps (set the source in ⚙). You can still tap it yourself.",
                style = MaterialTheme.typography.bodySmall,
            )
            AutoOption("No — I'll tap it", autoType == null) { autoType = null }
            AutoOption("No phone for a while after waking", autoType == AutoType.WAKE_NO_PHONE) {
                if (autoType != AutoType.WAKE_NO_PHONE) autoMinutes = "30"
                autoType = AutoType.WAKE_NO_PHONE
            }
            AutoOption("Woke near my usual time", autoType == AutoType.WAKE_ON_SCHEDULE) {
                if (autoType != AutoType.WAKE_ON_SCHEDULE) autoMinutes = "60"
                autoType = AutoType.WAKE_ON_SCHEDULE
            }
            AutoOption("A walk of at least…", autoType == AutoType.WALK) {
                if (autoType != AutoType.WALK) autoMinutes = "20"
                autoType = AutoType.WALK
            }
            autoType?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(autoMinutes, { autoMinutes = it }, "Minutes", Modifier.width(110.dp))
                    Text(
                        when (t) {
                            AutoType.WAKE_NO_PHONE -> "without unlocking the phone after waking"
                            AutoType.WAKE_ON_SCHEDULE -> "either side of my usual wake time"
                            AutoType.WALK -> "of continuous walking, from my watch's steps"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()
        Text("If it slips", style = MaterialTheme.typography.titleMedium)
        Text(
            if (kind == Kind.DO) "Each step: after this many days without it, what does it mean, and what should I ask myself?"
            else "Each step: this many slips within this many days — what does it mean, and what should I ask myself?",
            style = MaterialTheme.typography.bodySmall,
        )
        ladder.forEachIndexed { i, r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (kind == Kind.AVOID) {
                            NumberField(r.slips, { r.slips = it }, "Slips", Modifier.width(80.dp))
                            Text("in")
                        }
                        NumberField(r.days, { r.days = it }, "Days", Modifier.width(80.dp))
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ladder.removeAt(i) }) { Text("Remove") }
                    }
                    OutlinedTextField(r.meaning, { r.meaning = it }, Modifier.fillMaxWidth(), label = { Text("What it means") })
                    OutlinedTextField(
                        r.ask, { r.ask = it }, Modifier.fillMaxWidth(),
                        label = { Text("Ask me (blank = just a label, no check-in)") },
                    )
                    OutlinedTextField(
                        r.likely, { r.likely = it }, Modifier.fillMaxWidth(),
                        label = { Text("Likely reasons, comma-separated") },
                    )
                }
            }
        }
        OutlinedButton(onClick = {
            val last = ladder.lastOrNull()?.days?.toIntOrNull() ?: 0
            ladder.add(RungDraft(Rung(days = last + 3)))
        }) { Text("+ Add step") }

        HorizontalDivider()
        Text("When it's going well", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            winAsk, { winAsk = it }, Modifier.fillMaxWidth(),
            label = { Text("Ask me (blank = \"What's making it work?\")") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val base = existing ?: Habit(id = newId(), name = "", created = LocalDate.now().toString())
                    onSave(
                        base.copy(
                            name = name.trim(),
                            kind = kind,
                            target = Target(
                                times = times.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                days = per.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                            ),
                            `when` = whenOf.trim(),
                            why = why.trim(),
                            goal = goal.trim(),
                            ladder = ladder.mapNotNull { it.build() }.sortedBy { rungOrder(kind, it) },
                            winAsk = winAsk.trim().ifBlank { null },
                            auto = autoType?.takeIf { kind == Kind.DO }?.let {
                                AutoRule(it, autoMinutes.toIntOrNull()?.coerceIn(1, 600) ?: 30)
                            },
                        ),
                    )
                },
            ) { Text("Save") }
            if (existing != null) {
                OutlinedButton(onClick = { onArchive(existing) }) { Text("Archive") }
            }
        }

        if (existing != null && history.isNotEmpty()) {
            HorizontalDivider()
            Text("What you've said about it", style = MaterialTheme.typography.titleMedium)
            history.forEach { e ->
                val label = when {
                    e.type == EventType.SKIP -> "skipped"
                    e.rung == WIN_RUNG -> "going well"
                    else -> existing.ladder.getOrNull(e.rung ?: 0)?.meaning?.ifBlank { null } ?: "slipping"
                }
                Column {
                    Text(
                        "${e.date ?: e.t.take(10)} · $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(e.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AutoOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Mild → serious: DO by days, AVOID by slip density. */
private fun rungOrder(kind: Kind, r: Rung): Double =
    if (kind == Kind.DO) r.days.toDouble() else r.slips.toDouble() / r.days

private fun defaultLadder() = listOf(
    Rung(days = 3, meaning = "Fine."),
    Rung(days = 7, meaning = "Concerning.", ask = "What's getting in the way?"),
)
