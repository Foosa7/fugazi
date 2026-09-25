package com.fugazi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fugazi.app.data.DEFAULT_KEYSTONE
import com.fugazi.app.data.DEFAULT_MESSAGE
import com.fugazi.app.ui.theme.FugaziTheme

/**
 * The whole product, at the bottom of the curve: one message, one action.
 * No list, no metrics, no "reflect on your week."
 */
class InterventionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_KEYSTONE = "keystone"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: DEFAULT_MESSAGE
        val keystone = intent.getStringExtra(EXTRA_KEYSTONE) ?: DEFAULT_KEYSTONE
        setContent {
            FugaziTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    InterventionScreen(
                        message = message,
                        keystone = keystone,
                        onDone = { finish() },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun InterventionScreen(
    message: String,
    keystone: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            keystone,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        ) { Text("On it") }
    }
}
