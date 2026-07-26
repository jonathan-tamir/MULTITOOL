package com.jonathan.multitool.ui

import android.graphics.Bitmap
import kotlin.math.ln

/** Maps normalized magnitudes to a black → accent → white heat map bitmap. */
fun magnitudeToBitmap(mag: DoubleArray, w: Int, h: Int, accentArgb: Int): Bitmap {
    var max = 0.0
    for (v in mag) if (v > max) max = v
    val logMax = ln(1.0 + max)
    val ar = (accentArgb shr 16) and 0xFF
    val ag = (accentArgb shr 8) and 0xFF
    val ab = accentArgb and 0xFF
    val px = IntArray(w * h)
    for (i in mag.indices) {
        val t = if (logMax > 0.0) ln(1.0 + mag[i]) / logMax else 0.0
        px[i] = tone(t, ar, ag, ab)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun tone(tIn: Double, ar: Int, ag: Int, ab: Int): Int {
    val v = tIn.coerceIn(0.0, 1.0)
    val r: Int
    val g: Int
    val b: Int
    if (v < 0.65) {
        val k = v / 0.65
        r = (ar * k).toInt()
        g = (ag * k).toInt()
        b = (ab * k).toInt()
    } else {
        val k = (v - 0.65) / 0.35
        r = (ar + (255 - ar) * k).toInt()
        g = (ag + (255 - ag) * k).toInt()
        b = (ab + (255 - ab) * k).toInt()
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
