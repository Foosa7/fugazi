package com.fugazi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fugazi.app.data.SetupConfig

/**
 * The only configuration screen, shown once on a good day. The user writes the
 * intervention in their own words and confirms the late-night window + keystone.
 * Nothing here should grow into a settings menu.
 */
@Composable
fun SetupScreen(
    initial: SetupConfig,
    hasUsageAccess: Boolean,
    onRefreshAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSave: (message: String, keystone: String, lateStart: Int, lateEnd: Int) -> Unit,
) {
    var message by remember { mutableStateOf(initial.message) }
    var keystone by remember { mutableStateOf(initial.keystone) }
    var lateStart by remember { mutableStateOf(initial.lateStartHour.toString()) }
    var lateEnd by remember { mutableStateOf(initial.lateEndHour.toString()) }

    val startHour = lateStart.toIntOrNull()
    val endHour = lateEnd.toIntOrNull()
    val hoursValid = startHour in 0..23 && endHour in 0..23
    val canSave = message.isNotBlank() && keystone.isNotBlank() && hoursValid && hasUsageAccess

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set it up once, on a good day", style = MaterialTheme.typography.headlineSmall)
        Text(
            "fugazi watches your phone use in the background and says one thing, " +
                "once, when the valley is starting. This is the only setup.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("The message — in your own words") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = keystone,
            onValueChange = { keystone = it },
            label = { Text("The one action (shown in the notification)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Late-night window (24h)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = lateStart,
                onValueChange = { lateStart = it.filter(Char::isDigit).take(2) },
                label = { Text("From") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lateEnd,
                onValueChange = { lateEnd = it.filter(Char::isDigit).take(2) },
                label = { Text("To") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            if (hasUsageAccess) "Usage access: granted ✓"
            else "Usage access is required — it's how the radar sees anything.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onOpenUsageAccess() }) { Text("Grant usage access") }
            OutlinedButton(onClick = { onRefreshAccess() }) { Text("I've granted it") }
        }
        OutlinedButton(onClick = { onRequestNotifications() }) { Text("Allow notifications") }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onSave(message, keystone, startHour ?: 23, endHour ?: 3)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Arm the radar") }
    }
}
