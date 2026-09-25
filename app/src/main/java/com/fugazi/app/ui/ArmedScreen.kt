package com.fugazi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The resting state. Confirms the radar is armed and nothing else — no scores,
 * no charts, no streaks. Its job is to be boring and silent.
 */
@Composable
fun ArmedScreen(
    hasUsageAccess: Boolean,
    onRefreshAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRunCheckNow: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefreshAccess() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Radar armed.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "It stays silent until it matters.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (!hasUsageAccess) {
            Text(
                "Usage access is off — the radar is blind. Turn it back on.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            OutlinedButton(
                onClick = onOpenUsageAccess,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Fix usage access") }
        }

        // Small manual trigger, useful for verifying on a real device. Not a dashboard.
        TextButton(
            onClick = onRunCheckNow,
            modifier = Modifier.padding(top = 32.dp),
        ) { Text("Run a check now") }
    }
}
