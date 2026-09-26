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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fugazi.app.ai.ReflectWorker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fugazi.app.ai.AiSettings
import com.fugazi.app.ai.Reflector
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What the model made of each day. Yesterday's arrives by itself in the morning; the
 * button asks about today so far. Answer its questions in Thoughts — it reads them next time.
 */
@Composable
fun ReflectScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val days by Reflector.days.collectAsState()
    val version by Reflector.version.collectAsState()
    var day by remember { mutableStateOf(days.firstOrNull() ?: today) }
    val hasKey = remember { AiSettings.key(context).isNotBlank() }
    val text = remember(day, version) { Reflector.read(day) }

    // The reflection runs as a background job, so it survives leaving this tab or the app.
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ReflectWorker.WORK) }
        .collectAsState(initial = emptyList())
    val job = work.firstOrNull()
    val busy = job?.state == WorkInfo.State.RUNNING || job?.state == WorkInfo.State.ENQUEUED
    val error = job?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString(ReflectWorker.KEY_ERROR)
    val working = job?.let { runCatching { LocalDate.parse(it.tags.firstOrNull { t -> t.startsWith("day:") }?.removePrefix("day:")) }.getOrNull() }
    // When a reflection finishes, show it.
    LaunchedEffect(job?.state) {
        if (job?.state == WorkInfo.State.SUCCEEDED) working?.let { day = it }
    }

    fun ask(d: LocalDate) = ReflectWorker.start(context, d)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (day == today) "Today so far" else day.format(LONG),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (!hasKey) {
            Text("Add a Kimi API key in Settings and fugazi will read your days back to you each morning.")
            Button(onClick = onSettings) { Text("Open Settings") }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { ask(today) }, enabled = !busy) { Text("Reflect on today") }
            if (Reflector.read(today.minusDays(1)).isBlank()) {
                OutlinedButton(onClick = { ask(today.minusDays(1)) }, enabled = !busy) { Text("Yesterday") }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
        }
        if (busy) Text(
            "Kimi is reading your day — this can take a few minutes. You can leave; you'll get a notification when it's ready.",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (text.isNotBlank()) {
            text.trim().lines().forEach { line ->
                if (line.startsWith("#")) Text(line.trimStart('#', ' '), style = MaterialTheme.typography.titleMedium)
                else if (line.isNotBlank()) Text(line.replace("**", ""), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Sleep on the questions, then answer them in tomorrow's Thoughts — it reads your answers next time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!busy) {
            Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val earlier = days.filter { it != day }
        if (earlier.isNotEmpty()) {
            HorizontalDivider()
            Text("Earlier", style = MaterialTheme.typography.titleMedium)
            earlier.forEach { d ->
                Column(Modifier.fillMaxWidth().clickable { day = d }.padding(vertical = 8.dp)) {
                    Text(if (d == today) "Today" else d.format(LONG), style = MaterialTheme.typography.labelLarge)
                    Text(
                        Reflector.read(d).lineSequence().map { it.trimStart('#', ' ') }
                            .filter { it.isNotBlank() && it != "The day" }.firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (day != today && days.isNotEmpty()) TextButton(onClick = { day = days.first() }) { Text("Back to latest") }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM")
