package com.jonathan.multitool.core.drone

import kotlin.math.cos
import kotlin.math.sin

/** Minimal radix-2 iterative complex FFT (size must be a power of two).
 *  Produces the same result as numpy.fft.rfft's magnitude on real input. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        for (i in 0 until n / 2) {
            val a = -2.0 * Math.PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        var j = 0
        for (i in 0 until n) {
            rev[i] = j
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
        }
    }

    /** In-place FFT of re/im arrays (length n). */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val a = i + m; val b = i + m + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr;        im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Power spectrum |X|^2 for bins 0..n/2 (length n/2+1). Input `frame` length n. */
    fun powerSpectrum(frame: FloatArray, outPower: FloatArray) {
        val re = frame.copyOf()
        val im = FloatArray(n)
        transform(re, im)
        for (k in 0..n / 2) outPower[k] = re[k] * re[k] + im[k] * im[k]
    }
}
