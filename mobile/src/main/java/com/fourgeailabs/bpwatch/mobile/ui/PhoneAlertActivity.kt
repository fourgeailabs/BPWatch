package com.fourgeailabs.bpwatch.mobile.ui

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen takeover for EXTREME watch alerts (dangerously high/low heart
 * rate or blood pressure). Fires over the lock screen and can only be
 * dismissed with a firm horizontal swipe — no tap-to-dismiss, so it can't be
 * cleared by accident.
 */
class PhoneAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        buzz()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifEmpty { "Health alert" }
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        setContent {
            BpWatchTheme {
                ExtremeAlertScreen(
                    title = title,
                    message = message,
                    onDismissed = { finish() },
                )
            }
        }
    }

    private fun buzz() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 250, 500, 250, 1000),
                    -1,
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TYPE = "extra_type"
    }
}

@Composable
private fun ExtremeAlertScreen(
    title: String,
    message: String,
    onDismissed: () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = 300f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB3261E))
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .alpha((1f - (abs(offsetX) / 1500f)).coerceIn(0.4f, 1f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> offsetX += dragAmount },
                    onDragEnd = {
                        if (abs(offsetX) > dismissThresholdPx) {
                            onDismissed()
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = { offsetX = 0f },
                )
            }
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Text(
                text = "⟵  swipe to dismiss  ⟶",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
