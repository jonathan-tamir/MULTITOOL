package com.jonathan.multitool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** One backdrop pattern per category, hinting at the physics it measures. */
enum class Motif { Spectrum, Grid, Axes, Radar, Scan }

/** Draws a motif at the given alpha behind the content. Scale is in px-equivalent units. */
fun Modifier.motif(kind: Motif, color: Color, alpha: Float = 0.5f, scale: Float = 1f): Modifier =
    this.drawBehind { drawMotif(kind, color.copy(alpha = color.alpha * alpha), scale) }

fun DrawScope.drawMotif(kind: Motif, color: Color, scale: Float = 1f) {
    val stroke = Stroke(width = 1f * density)
    when (kind) {
        Motif.Spectrum -> {
            val step = 7f * density * scale
            var x = 0f
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width)
                x += step
            }
        }
        Motif.Grid -> {
            val step = 13f * density * scale
            var x = 0f
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width); x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width); y += step
            }
        }
        Motif.Axes -> {
            val step = 34f * density * scale
            var x = 0f
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width); x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width); y += step
            }
        }
        Motif.Scan -> {
            val step = 9f * density * scale
            var y = 0f
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width); y += step
            }
        }
        Motif.Radar -> {
            val step = 26f * density * scale
            val c = Offset(size.width * 0.5f, size.height * 0.42f)
            val maxR = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
            var r = step
            while (r < maxR) {
                drawCircle(color, radius = r, center = c, style = stroke); r += step
            }
        }
    }
}
