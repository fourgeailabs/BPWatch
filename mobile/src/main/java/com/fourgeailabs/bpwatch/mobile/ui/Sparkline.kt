package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.data.Reading

/**
 * Tiny 14-point systolic/diastolic trend chart drawn with Canvas
 * (no chart library dependency).
 */
@Composable
fun BpSparkline(readings: List<Reading>) {
    val points = readings
        .mapNotNull { r ->
            val sys = r.sysEstimate ?: r.sysCuff
            val dia = r.diaEstimate ?: r.diaCuff
            if (sys != null && dia != null) Triple(r.timestamp, sys, dia) else null
        }
        .take(14)
        .reversed()

    if (points.size < 2) {
        Text(
            text = "Take a few readings and your trend will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    val sysColor = MaterialTheme.colorScheme.primary
    val diaColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(vertical = 8.dp)
    ) {
        val allValues = points.flatMap { listOf(it.second, it.third) }
        val min = (allValues.minOrNull() ?: 0) - 5f
        val max = (allValues.maxOrNull() ?: 1) + 5f
        val span = (max - min).coerceAtLeast(1f)

        fun x(i: Int) = size.width * i / (points.size - 1).coerceAtLeast(1).toFloat()
        fun y(v: Int) = size.height - ((v - min) / span) * size.height

        fun line(values: List<Int>): Path {
            val path = Path()
            values.forEachIndexed { i, v ->
                val p = Offset(x(i), y(v))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            return path
        }

        drawPath(line(points.map { it.second }), sysColor, style = Stroke(width = 5f))
        drawPath(line(points.map { it.third }), diaColor, style = Stroke(width = 5f))
    }
}
