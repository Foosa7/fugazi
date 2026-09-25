package com.fugazi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fugazi.app.sense.AutoMarker
import com.fugazi.app.sense.SleepSource
import java.time.format.DateTimeFormatter

/** Everything about the phone's sensors, in one place. Nothing here is about your habits. */
@Composable
fun SettingsScreen(
    source: SleepSource,
    sleepAccess: Boolean,
    backgroundAccess: Boolean?,
    usageAccess: Boolean,
    musicAccess: Boolean,
    onOpenMusicAccess: () -> Unit,
    status: AutoMarker.Status?,
    onSource: (SleepSource) -> Unit,
    onConnect: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.weight(1f))
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }

        Text("Sleep source", style = MaterialTheme.typography.titleMedium)
        Text(
            "Where your wake time comes from. Habits set to auto-mark from sleep use it.",
            style = MaterialTheme.typography.bodySmall,
        )
        SleepSource.entries.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onSource(s) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s == source, onClick = { onSource(s) })
                Text(s.label)
            }
        }
        Text(
            "The wearable's app also has to be allowed to write sleep: Open Health Connect → " +
                "App permissions → Fitbit (or Samsung Health) → Sleep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        Check("Read sleep", sleepAccess)
        backgroundAccess?.let { Check("Read sleep in the background", it) }
        status?.let { st ->
            Text(st.note, style = MaterialTheme.typography.bodyMedium)
            st.lastWake?.let {
                Text(
                    "Last wake seen: ${it.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sleepAccess || backgroundAccess == false) Button(onClick = onConnect) { Text("Connect") }
            OutlinedButton(onClick = onOpenHealthConnect) { Text("Open Health Connect") }
        }

        HorizontalDivider()
        Text("Phone usage", style = MaterialTheme.typography.titleMedium)
        Check("Usage access (to see when you first unlock)", usageAccess)
        if (!usageAccess) Button(onClick = onOpenUsageAccess) { Text("Grant usage access") }

        HorizontalDivider()
        Text("Music", style = MaterialTheme.typography.titleMedium)
        Text(
            "Counts songs started and skipped, and long plays like podcasts. Android calls this " +
                "notification access; fugazi never reads your notifications or records what you play.",
            style = MaterialTheme.typography.bodySmall,
        )
        Check("Media access", musicAccess)
        if (!musicAccess) Button(onClick = onOpenMusicAccess) { Text("Grant media access") }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text(
        (if (ok) "✓  " else "✗  ") + label,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}
