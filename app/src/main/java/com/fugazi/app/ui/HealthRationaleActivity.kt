package com.fugazi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fugazi.app.ui.theme.FugaziTheme

/** Shown by Health Connect when you ask what fugazi does with your sleep data. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FugaziTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How fugazi uses your sleep", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "fugazi reads only when your sleep ended, to know when you woke up. It uses that " +
                                "to mark habits like \"no phone after waking\" and \"wake on a schedule\" for you.",
                        )
                        Text("Nothing leaves your phone unless you export your journal yourself.")
                    }
                }
            }
        }
    }
}
