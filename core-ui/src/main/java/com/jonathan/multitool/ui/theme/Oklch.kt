package com.jonathan.multitool.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * OkLCH -> sRGB. The design comp specifies every accent as oklch(L C H); keeping the
 * conversion in code (instead of baking hex) means a new category is still one hue number.
 */
fun oklch(l: Float, c: Float, hueDeg: Float): Color {
    val h = (hueDeg * PI / 180.0).toFloat()
    val a = c * cos(h)
    val b = c * sin(h)
    val lp = l + 0.3963377774f * a + 0.2158037573f * b
    val mp = l - 0.1055613458f * a - 0.0638541728f * b
    val sp = l - 0.0894841775f * a - 1.2914855480f * b
    val L = lp * lp * lp
    val Mv = mp * mp * mp
    val S = sp * sp * sp
    val r = 4.0767416621f * L - 3.3077115913f * Mv + 0.2309699292f * S
    val g = -1.2684380046f * L + 2.6097574011f * Mv - 0.3413193965f * S
    val bl = -0.0041960863f * L - 0.7034186147f * Mv + 1.7076147010f * S
    return Color(gamma(r), gamma(g), gamma(bl))
}

private fun gamma(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

/** Category accent: equal chroma across hues, lightened for dark UI, darkened for light UI. */
fun accentFor(hue: Float, dark: Boolean): Color =
    if (dark) oklch(0.80f, 0.12f, hue) else oklch(0.55f, 0.15f, hue)
