package com.jonathan.multitool.core.image

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedOutputStream
import com.jonathan.multitool.core.dsp.Fft
import com.jonathan.multitool.core.media.MediaSave
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

object ImageMath {

    /** RGB channels of a bitmap scaled to n x n, values 0..255. */
    class Channels(val n: Int, val r: DoubleArray, val g: DoubleArray, val b: DoubleArray)

    /** Forward FFTs of all three channels (unshifted), plus shifted luminance magnitude. */
    class FftChannels(
        val n: Int,
        val re: Array<DoubleArray>, val im: Array<DoubleArray>,
        val shiftedMag: DoubleArray
    )

    fun channelsFromBitmap(src: Bitmap, n: Int): Channels {
        val scaled = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        scaled.getPixels(px, 0, n, 0, 0, n, n)
        val r = DoubleArray(n * n)
        val g = DoubleArray(n * n)
        val b = DoubleArray(n * n)
        for (i in px.indices) {
            val c = px[i]
            r[i] = ((c shr 16) and 0xFF).toDouble()
            g[i] = ((c shr 8) and 0xFF).toDouble()
            b[i] = (c and 0xFF).toDouble()
        }
        return Channels(n, r, g, b)
    }

    fun forward(ch: Channels): FftChannels {
        val n = ch.n
        val re = Array(3) { DoubleArray(n * n) }
        val im = Array(3) { DoubleArray(n * n) }
        val src = arrayOf(ch.r, ch.g, ch.b)
        for (c in 0 until 3) {
            System.arraycopy(src[c], 0, re[c], 0, n * n)
            Fft.fft2(re[c], im[c], n, n)
        }
        val mag = DoubleArray(n * n)
        for (y in 0 until n) {
            val sy = (y + n / 2) % n
            for (x in 0 until n) {
                val sx = (x + n / 2) % n
                val i = y * n + x
                // luminance-ish magnitude
                val m = 0.30 * hyp(re[0][i], im[0][i]) +
                    0.59 * hyp(re[1][i], im[1][i]) +
                    0.11 * hyp(re[2][i], im[2][i])
                mag[sy * n + sx] = m
            }
        }
        return FftChannels(n, re, im, mag)
    }

    private fun hyp(a: Double, b: Double) = sqrt(a * a + b * b)

    /** Frequency radius (unshifted coords) for index (x, y). */
    private fun radius(x: Int, y: Int, n: Int): Double {
        val fx = min(x, n - x).toDouble()
        val fy = min(y, n - y).toDouble()
        return sqrt(fx * fx + fy * fy)
    }

    /** Soft low-pass mask: 1 inside cutoff, smooth logistic rolloff. */
    fun lowpassMask(n: Int, cutoff: Double, softness: Double): DoubleArray {
        val s = softness.coerceAtLeast(0.5)
        val out = DoubleArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val r = radius(x, y, n)
                out[y * n + x] = 1.0 / (1.0 + exp((r - cutoff) / s))
            }
        }
        return out
    }

    fun highpassMask(n: Int, cutoff: Double, softness: Double): DoubleArray {
        val lp = lowpassMask(n, cutoff, softness)
        for (i in lp.indices) lp[i] = 1.0 - lp[i]
        // always keep DC so overall brightness survives
        lp[0] = 1.0
        return lp
    }

    /** Removes a ring of spatial frequencies (moire / scan-line killer). */
    fun bandstopMask(n: Int, center: Double, width: Double): DoubleArray {
        val w = width.coerceAtLeast(0.5)
        val out = DoubleArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val r = radius(x, y, n)
                val d = r - center
                out[y * n + x] = 1.0 - exp(-(d * d) / (2.0 * w * w))
            }
        }
        out[0] = 1.0
        return out
    }

    /** Applies an unshifted mask to all channels and inverse-transforms to a bitmap. */
    fun applyMask(f: FftChannels, mask: DoubleArray): Bitmap {
        val n = f.n
        val out = IntArray(n * n)
        val res = Array(3) { DoubleArray(n * n) }
        for (c in 0 until 3) {
            val re = f.re[c].copyOf()
            val im = f.im[c].copyOf()
            for (i in re.indices) {
                re[i] *= mask[i]
                im[i] *= mask[i]
            }
            Fft.ifft2(re, im, n, n)
            res[c] = re
        }
        for (i in out.indices) {
            val r = res[0][i].coerceIn(0.0, 255.0).toInt()
            val g = res[1][i].coerceIn(0.0, 255.0).toInt()
            val b = res[2][i].coerceIn(0.0, 255.0).toInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Applies a SHIFTED-coords mask (as shown on screen) to all channels. */
    fun applyShiftedMask(f: FftChannels, maskShifted: DoubleArray): Bitmap {
        val n = f.n
        val unshifted = DoubleArray(n * n)
        for (y in 0 until n) {
            val sy = (y + n / 2) % n
            for (x in 0 until n) {
                val sx = (x + n / 2) % n
                unshifted[y * n + x] = maskShifted[sy * n + sx]
            }
        }
        return applyMask(f, unshifted)
    }

    /** Carves a soft hole at (px, py) in shifted coords, plus its symmetric twin. */
    fun carveHole(maskShifted: DoubleArray, n: Int, px: Int, py: Int, holeRadius: Double) {
        fun carveAt(cx: Int, cy: Int) {
            val r2 = 2.0 * holeRadius * holeRadius
            val reach = (holeRadius * 3).toInt() + 1
            for (dy in -reach..reach) {
                val y = cy + dy
                if (y < 0 || y >= n) continue
                for (dx in -reach..reach) {
                    val x = cx + dx
                    if (x < 0 || x >= n) continue
                    val d2 = (dx * dx + dy * dy).toDouble()
                    val keep = 1.0 - exp(-d2 / r2)
                    val i = y * n + x
                    if (keep < maskShifted[i]) maskShifted[i] = keep
                }
            }
        }
        carveAt(px, py)
        // conjugate-symmetric twin keeps the result a real image
        carveAt((n - px) % n, (n - py) % n)
    }

    /** Low frequencies of A + high frequencies of B. */
    fun hybrid(a: Channels, b: Channels, cutoff: Double, softness: Double): Bitmap {
        val n = a.n
        val lp = lowpassMask(n, cutoff, softness)
        val out = IntArray(n * n)
        val res = Array(3) { DoubleArray(n * n) }
        val chA = arrayOf(a.r, a.g, a.b)
        val chB = arrayOf(b.r, b.g, b.b)
        for (c in 0 until 3) {
            val reA = chA[c].copyOf()
            val imA = DoubleArray(n * n)
            Fft.fft2(reA, imA, n, n)
            val reB = chB[c].copyOf()
            val imB = DoubleArray(n * n)
            Fft.fft2(reB, imB, n, n)
            for (i in reA.indices) {
                val m = lp[i]
                val hm = if (i == 0) 0.0 else 1.0 - m
                reA[i] = reA[i] * m + reB[i] * hm
                imA[i] = imA[i] * m + imB[i] * hm
            }
            Fft.ifft2(reA, imA, n, n)
            res[c] = reA
        }
        for (i in out.indices) {
            val r = res[0][i].coerceIn(0.0, 255.0).toInt()
            val g = res[1][i].coerceIn(0.0, 255.0).toInt()
            val bb = res[2][i].coerceIn(0.0, 255.0).toInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Saves a bitmap as PNG. Optionally rescales to [outW] x [outH] first. */
    fun saveBitmap(context: Context, bmp: Bitmap, name: String, outW: Int = 0, outH: Int = 0): String {
        val toSave = if (outW > 0 && outH > 0 && (outW != bmp.width || outH != bmp.height)) {
            Bitmap.createScaledBitmap(bmp, outW, outH, true)
        } else bmp
        val target = MediaSave.openImage(context, name)
        BufferedOutputStream(target.stream, 1 shl 16).use { out ->
            toSave.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        target.finish()
        return target.location
    }
}
