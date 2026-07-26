package com.jonathan.multitool.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jonathan.multitool.ui.theme.LocalShell
import com.jonathan.multitool.ui.theme.Mono

const val CAT_ZOOM_MS = 620
const val SIGNAL_MS = 1560

private val ZoomEase = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
private val SplitEase = CubicBezierEasing(0.7f, 0f, 0.2f, 1f)
private val RipEase = CubicBezierEasing(0.5f, 0f, 0.4f, 1f)

@Composable
private fun clock(durationMs: Int): Float {
    // Off-device rendering can't advance an animation, so show a representative frame instead.
    if (LocalInspectionMode.current) return 0.35f
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(durationMs, easing = LinearEasing)) }
    return a.value
}

private fun span(t: Float, from: Float, to: Float): Float =
    ((t - from) / (to - from)).coerceIn(0f, 1f)

/** Ink used behind a takeover: near-black in dark, near-paper in light. */
@Composable
private fun overlayInk(): Color = if (LocalShell.current.dark) Color(0xFF07080A) else Color(0xFFF2F0EC)

/** Motif line colour on top of the takeover ink. */
@Composable
private fun motifInk(): Color =
    if (LocalShell.current.dark) Color(1f, 1f, 1f, 0.10f) else Color(0f, 0f, 0f, 0.13f)

/**
 * Category entry — "zoom grid" (620 ms). The category's motif scales up through the screen
 * while its code holds centre, then the whole thing dissolves onto the subspace.
 */
@Composable
fun CatZoomOverlay(motif: Motif, accent: Color, code: String) {
    val t = clock(CAT_ZOOM_MS)
    val e = ZoomEase.transform(t)
    val ink = overlayInk()
    // keyframes: opacity 0 -> 1 @60% -> 0 ; scale .55 -> 1.35
    val motifAlpha = if (e < 0.6f) span(e, 0f, 0.6f) else 1f - span(e, 0.6f, 1f)
    val scale = 0.55f + 0.80f * e
    val out = 1f - span(t, 0.484f, 0.968f)   // ovOut .3s @ .3s of 620ms

    Box(
        Modifier
            .fillMaxSize()
            .alpha(out)
            .drawBehind { drawRect(ink) }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = motifAlpha }
                .motif(motif, motifInk(), alpha = 1f)
        )
        androidx.compose.material3.Text(
            code,
            style = Mono.code,
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).alpha(span(t, 0f, 0.4f))
        )
    }
}

/**
 * Utility takeover — "signal ignition" (1560 ms), shared by every utility.
 * A waveform is drawn across the screen while a glowing scan column rips left to right,
 * then the screen splits horizontally and slides open onto the tool.
 */
@Composable
fun SignalOverlay(accent: Color, label: String) {
    val t = clock(SIGNAL_MS)
    val ink = overlayInk()
    val split = SplitEase.transform(t)
    val open = span(split, 0.52f, 1f)          // 0 until the halves start moving
    val rip = RipEase.transform(span(t, 0f, 0.513f))   // .8s of 1560ms
    val inner = 1f - span(t, 0.5f, 0.692f)     // ovOut .3s @ .78s
    val labelAlpha = span(t, 0.224f, 0.417f)

    Box(Modifier.fillMaxSize()) {
        // the two halves that slide apart
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -open * 1.01f * size.height }
                .drawBehind {
                    drawRect(ink, size = androidx.compose.ui.geometry.Size(size.width, size.height / 2f))
                    drawLine(
                        accent.copy(alpha = 0.4f),
                        Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
                        strokeWidth = 1f * density
                    )
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = open * 1.01f * size.height }
                .drawBehind {
                    drawRect(
                        ink,
                        topLeft = Offset(0f, size.height / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height / 2f)
                    )
                    drawLine(
                        accent.copy(alpha = 0.4f),
                        Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
                        strokeWidth = 1f * density
                    )
                }
        )

        // waveform + scan column + label, all fading together once the split begins
        Canvas(Modifier.fillMaxSize().alpha(inner)) {
            val sx = size.width / 412f
            val sy = size.height / 892f
            val p = Path().apply {
                moveTo(0f * sx, 446f * sy)
                cubicTo(40f * sx, 446f * sy, 46f * sx, 300f * sy, 70f * sx, 300f * sy)
                cubicTo(96f * sx, 300f * sy, 100f * sx, 590f * sy, 132f * sx, 590f * sy)
                cubicTo(160f * sx, 590f * sy, 166f * sx, 250f * sy, 196f * sx, 250f * sy)
                cubicTo(226f * sx, 250f * sy, 232f * sx, 640f * sy, 262f * sx, 640f * sy)
                cubicTo(292f * sx, 640f * sy, 296f * sx, 380f * sy, 330f * sx, 380f * sy)
                cubicTo(362f * sx, 380f * sy, 368f * sx, 470f * sy, 412f * sx, 470f * sy)
            }
            val pm = PathMeasure().apply { setPath(p, false) }
            val drawn = Path()
            pm.getSegment(0f, pm.length * rip, drawn, true)
            drawStroke(drawn, accent, 2f * density)

            // scan column: -6% -> 106% of width, with a soft glow
            val x = size.width * (-0.06f + 1.12f * rip)
            val glow = 22f * density
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to accent.copy(alpha = 0.33f),
                    1f to Color.Transparent
                ),
                topLeft = Offset(x - glow, 0f),
                size = androidx.compose.ui.geometry.Size(glow * 2f, size.height)
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent, 0.5f to accent, 1f to Color.Transparent
                ),
                topLeft = Offset(x - 1f * density, 0f),
                size = androidx.compose.ui.geometry.Size(2f * density, size.height)
            )
        }
        androidx.compose.material3.Text(
            label.uppercase(),
            style = Mono.code,
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = 0f, verticalBias = 0.08f))  // top: 54%
                .fillMaxWidth()
                .alpha(inner * labelAlpha)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
    path: Path, color: Color, width: Float
) = drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
