package com.fourgeailabs.bpwatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

/**
 * Full-screen alert shown (via a high-priority notification's full-screen
 * intent) when a heart-rate or blood-pressure threshold trips. Shows the
 * offending value and which limit it crossed, with a Dismiss button.
 */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Health alert"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        setContent {
            MaterialTheme {
                AlertScreen(title = title, message = message, onDismiss = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "alert_title"
        const val EXTRA_MESSAGE = "alert_message"
    }
}

@Composable
private fun AlertScreen(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.display2,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.title2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("Dismiss", textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
