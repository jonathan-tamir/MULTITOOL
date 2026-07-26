package com.jonathan.multitool.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jonathan.multitool.core.dsp.Fft
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

fun formatHz(f: Double): String =
    if (f >= 1000.0) String.format("%.2f kHz", f / 1000.0) else String.format("%.1f Hz", f)

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
fun SpectrumPlot(
    mags: DoubleArray?,
    binHz: Double,
    logAxis: Boolean,
    showGrid: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    minFreq: Double = 20.0,
    maxFreq: Double = 20000.0,
    probeFreq: Double? = null,
    dbFloor: Double = -100.0
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    val labelPaint = remember(labelArgb) {
        android.graphics.Paint().apply {
            color = labelArgb
            textSize = 26f
            isAntiAlias = true
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        fun freqAtX(x: Float): Double =
            if (logAxis) minFreq * (maxFreq / minFreq).pow((x / w).toDouble())
            else minFreq + (maxFreq - minFreq) * (x / w)

        fun xAtFreq(f: Double): Float =
            if (logAxis) (w * (ln(f / minFreq) / ln(maxFreq / minFreq))).toFloat()
            else (w * ((f - minFreq) / (maxFreq - minFreq))).toFloat()

        if (showGrid) {
            var db = -20
            while (db > dbFloor.toInt()) {
                val y = h * (db / dbFloor).toFloat()
                drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
                db -= 20
            }
            val ticks = if (logAxis)
                doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
            else {
                val span = maxFreq - minFreq
                val step = when {
                    span > 10000 -> 5000.0
                    span > 2000 -> 1000.0
                    span > 200 -> 100.0
                    span > 20 -> 10.0
                    else -> 2.0
                }
                var t = (minFreq / step).toInt() * step + step
                val list = ArrayList<Double>()
                while (t < maxFreq) { list.add(t); t += step }
                list.toDoubleArray()
            }
            for (f in ticks) {
                if (f <= minFreq || f >= maxFreq) continue
                val x = xAtFreq(f)
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}",
                    x + 6f, h - 8f, labelPaint
                )
            }
        }

        if (mags != null && mags.size >= 4 && binHz > 0.0) {
            val steps = w.toInt().coerceIn(2, 720)
            val fillPath = Path()
            val linePath = Path()
            fillPath.moveTo(0f, h)
            var prevBin = -1
            for (s in 0..steps) {
                val x = w * s / steps
                val f = freqAtX(x)
                val bin = (f / binHz).toInt()
                var m = 0.0
                val lo = if (prevBin < 0) bin else minOf(prevBin + 1, bin)
                var b = lo
                while (b <= bin) {
                    if (b in mags.indices) { if (mags[b] > m) m = mags[b] }
                    b++
                }
                prevBin = bin
                val db = 20.0 * log10(m + 1e-12)
                val frac = ((db - dbFloor) / (0.0 - dbFloor)).coerceIn(0.0, 1.0)
                val y = (h * (1.0 - frac)).toFloat()
                fillPath.lineTo(x, y)
                if (s == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            fillPath.lineTo(w, h)
            fillPath.close()
            drawPath(
                fillPath,
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.5f), accent.copy(alpha = 0.04f)),
                    startY = 0f, endY = h
                )
            )
            drawPath(linePath, accent, style = Stroke(width = 3f))
        }

        if (probeFreq != null && probeFreq > minFreq && probeFreq < maxFreq) {
            val x = xAtFreq(probeFreq)
            drawLine(
                accent.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, h), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        }
    }
}

@Composable
fun PeakChips(peaks: List<Fft.Peak>, accent: Color, showNote: Boolean = true, unit: String = "") {
    if (peaks.isEmpty()) {
        Text(
            "Listening for peaks…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        peaks.forEachIndexed { i, p ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (i == 0) accent.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val label = if (unit.isEmpty()) formatHz(p.freq)
                    else String.format("%.1f %s", p.freq, unit)
                    val note = if (showNote) Fft.noteName(p.freq) else ""
                    Text(
                        if (note.isEmpty()) label else "$label · $note",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        String.format("%.1f dB", 20.0 * log10(p.magnitude + 1e-12)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ProbeCard(
    title: String,
    unitLabel: String,
    text: String,
    onTextChange: (String) -> Unit,
    participation: Double?,
    detail: String?,
    accent: Color
) {
    SectionCard(title) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(unitLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (participation != null) {
            val pct = participation * 100.0
            Text(
                if (pct >= 0.1) String.format("%.1f%% of total signal power", pct)
                else String.format("%.4f%% of total signal power", pct),
                fontWeight = FontWeight.SemiBold
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(participation.toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(accent)
                )
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "Enter a frequency to see its share of the signal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(50)
            )
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun SmallNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
