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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fugazi.app.sense.AutoMarker
import com.fugazi.app.sync.DriveSync
import com.fugazi.app.ai.Kimi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
    aiKey: String,
    aiDaily: Boolean,
    onAiKey: (String) -> Unit,
    onAiDaily: (Boolean) -> Unit,
    aiModel: String,
    aiEffort: String,
    onAiModel: (String) -> Unit,
    onAiEffort: (String) -> Unit,
    drive: DriveSync.Status,
    onDriveConnect: () -> Unit,
    onDriveSyncNow: () -> Unit,
    onDriveKeepPhone: () -> Unit,
    onDriveRestore: () -> Unit,
    onDriveDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    var key by remember { mutableStateOf(aiKey) }
    var daily by remember { mutableStateOf(aiDaily) }
    var savedKey by remember { mutableStateOf(aiKey) }
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

        HorizontalDivider()
        DriveSection(drive, onDriveConnect, onDriveSyncNow, onDriveKeepPhone, onDriveRestore, onDriveDisconnect)

        HorizontalDivider()
        Text("Reflection (Kimi K3)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your last two weeks — habits, what the phone saw, and what you wrote — are sent to " +
                "Moonshot's Kimi API when a reflection runs. The key stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAiKey(key); savedKey = key.trim() }, enabled = key.trim() != savedKey) { Text("Save key") }
            if (savedKey.isNotBlank()) Check("Key saved", true)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Read yesterday back to me each morning", Modifier.weight(1f))
            Switch(checked = daily, onCheckedChange = { daily = it; onAiDaily(it) })
        }
        ModelSection(aiModel, aiEffort, onAiModel, onAiEffort)
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text(
        (if (ok) "✓  " else "✗  ") + label,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** Backup of the whole journal to a "fugazi" folder in your Google Drive. */
@Composable
private fun DriveSection(
    status: DriveSync.Status,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onKeepPhone: () -> Unit,
    onRestore: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirmRestore by remember { mutableStateOf(false) }
    Text("Google Drive backup", style = MaterialTheme.typography.titleMedium)
    Text(
        "Your thoughts, habits, the phone's signals and the AI's reflections are copied to a " +
            "\"fugazi\" folder in your Drive shortly after you leave the app and every few hours. " +
            "fugazi can only see files it made there, and it never deletes anything in Drive.",
        style = MaterialTheme.typography.bodySmall,
    )
    when (status) {
        DriveSync.Status.Off -> {
            Text("Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onConnect) { Text("Connect Google Drive") }
        }
        DriveSync.Status.Syncing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Syncing…")
        }
        is DriveSync.Status.Synced -> {
            Check("Backed up ${ago(status.atMs)} · ${status.files} files", true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow) { Text("Sync now") }
                OutlinedButton(onClick = { confirmRestore = true }) { Text("Restore…") }
            }
            TextButton(onClick = onDisconnect) { Text("Stop backing up") }
        }
        is DriveSync.Status.Failed -> {
            Check(status.message, false)
            if (status.lastOkMs > 0) Text("Last good backup ${ago(status.lastOkMs)}.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text("Reconnect") }
                OutlinedButton(onClick = onSyncNow) { Text("Try again") }
            }
        }
        is DriveSync.Status.FoundBackup -> {
            Text(
                "There's already a fugazi backup in your Drive (last changed ${status.modified.take(10)}). " +
                    "Bring it to this phone, or replace it with what's on this phone? Drive keeps old versions either way.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmRestore = true }) { Text("Restore it here") }
                OutlinedButton(onClick = onKeepPhone) { Text("Use this phone's") }
            }
        }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore from Drive?") },
        text = {
            Text(
                "This phone's journal is replaced with the copy in Drive, and the app restarts. " +
                    "The current journal is kept on the phone as a separate folder, not deleted.",
            )
        },
        confirmButton = { TextButton(onClick = { confirmRestore = false; onRestore() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
    )
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} days ago"
    }
}

/** Which Kimi model reflects, and how hard it thinks. Each reflection is signed with both. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSection(model: String, effort: String, onModel: (String) -> Unit, onEffort: (String) -> Unit) {
    var current by remember { mutableStateOf(model) }
    var effortNow by remember { mutableStateOf(effort) }
    val preset = Kimi.PRESETS.any { it.first == current }
    var custom by remember { mutableStateOf(if (preset) "" else current) }

    Text("Model", style = MaterialTheme.typography.titleSmall)
    Kimi.PRESETS.forEach { (id, label) ->
        Row(Modifier.fillMaxWidth().clickable { current = id; onModel(id) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = current == id, onClick = { current = id; onModel(id) })
            Text(label)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !preset, onClick = { if (custom.isNotBlank()) { current = custom.trim(); onModel(current) } })
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it; if (it.isNotBlank()) { current = it.trim(); onModel(current) } },
            label = { Text("Other model id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text("Thinking effort", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kimi.EFFORTS.forEach { e ->
            FilterChip(
                selected = effortNow == e,
                onClick = { effortNow = e; onEffort(e) },
                label = { Text(e) },
            )
        }
    }
    Text(
        "low is quickest, max is deepest and can take minutes. \"default\" lets the model decide — " +
            "use it if a model rejects the effort setting.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
