package com.jonathan.multitool.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Fft {

    /** In-place iterative radix-2 FFT. Size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            val half = len shr 1
            while (i < n) {
                var cRe = 1.0
                var cIm = 0.0
                for (k in 0 until half) {
                    val i2 = i + k + half
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i2] * cRe - im[i2] * cIm
                    val bIm = re[i2] * cIm + im[i2] * cRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i2] = aRe - bRe
                    im[i2] = aIm - bIm
                    val nRe = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** In-place inverse FFT (with 1/n scaling). */
    fun ifft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        for (i in 0 until n) {
            re[i] = re[i] / n
            im[i] = -im[i] / n
        }
    }

    /** In-place 2D FFT (rows then columns), no shifting. */
    fun fft2(re: DoubleArray, im: DoubleArray, w: Int, h: Int) {
        val rowRe = DoubleArray(w)
        val rowIm = DoubleArray(w)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) { rowRe[x] = re[off + x]; rowIm[x] = im[off + x] }
            fft(rowRe, rowIm)
            for (x in 0 until w) { re[off + x] = rowRe[x]; im[off + x] = rowIm[x] }
        }
        val colRe = DoubleArray(h)
        val colIm = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) { colRe[y] = re[y * w + x]; colIm[y] = im[y * w + x] }
            fft(colRe, colIm)
            for (y in 0 until h) { re[y * w + x] = colRe[y]; im[y * w + x] = colIm[y] }
        }
    }

    /** In-place 2D inverse FFT (rows then columns), no shifting. */
    fun ifft2(re: DoubleArray, im: DoubleArray, w: Int, h: Int) {
        val rowRe = DoubleArray(w)
        val rowIm = DoubleArray(w)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) { rowRe[x] = re[off + x]; rowIm[x] = im[off + x] }
            ifft(rowRe, rowIm)
            for (x in 0 until w) { re[off + x] = rowRe[x]; im[off + x] = rowIm[x] }
        }
        val colRe = DoubleArray(h)
        val colIm = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) { colRe[y] = re[y * w + x]; colIm[y] = im[y * w + x] }
            ifft(colRe, colIm)
            for (y in 0 until h) { re[y * w + x] = colRe[y]; im[y * w + x] = colIm[y] }
        }
    }

    fun hannWindow(n: Int): DoubleArray =
        DoubleArray(n) { 0.5 * (1.0 - cos(2.0 * PI * it / (n - 1))) }

    /** Hann-windowed magnitude spectrum, length n/2. */
    fun magnitudeSpectrum(samples: DoubleArray): DoubleArray {
        val n = samples.size
        val window = hannWindow(n)
        val re = DoubleArray(n) { samples[it] * window[it] }
        val im = DoubleArray(n)
        fft(re, im)
        val out = DoubleArray(n / 2)
        for (i in out.indices) {
            out[i] = 2.0 * sqrt(re[i] * re[i] + im[i] * im[i]) / n
        }
        return out
    }

    /**
     * 2D FFT magnitude of a row-major [w] x [h] grayscale image,
     * quadrant-shifted so DC sits at the center.
     */
    fun fft2Magnitude(gray: DoubleArray, w: Int, h: Int): DoubleArray {
        val re = gray.copyOf()
        val im = DoubleArray(w * h)
        fft2(re, im, w, h)
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            val sy = (y + h / 2) % h
            for (x in 0 until w) {
                val sx = (x + w / 2) % w
                val idx = y * w + x
                out[sy * w + sx] = sqrt(re[idx] * re[idx] + im[idx] * im[idx])
            }
        }
        return out
    }

    data class Peak(val freq: Double, val magnitude: Double)

    /** Local-maximum peak picking with a spacing constraint. */
    fun findPeaks(mags: DoubleArray, binHz: Double, count: Int, minFreq: Double = 20.0): List<Peak> {
        if (mags.size < 4 || binHz <= 0.0) return emptyList()
        val candidates = ArrayList<Peak>()
        val start = maxOf(1, (minFreq / binHz).toInt())
        for (i in start until mags.size - 1) {
            if (mags[i] > mags[i - 1] && mags[i] >= mags[i + 1] && mags[i] > 1e-7) {
                candidates.add(Peak(i * binHz, mags[i]))
            }
        }
        candidates.sortByDescending { it.magnitude }
        val picked = ArrayList<Peak>()
        for (c in candidates) {
            if (picked.size >= count) break
            if (picked.none { abs(it.freq - c.freq) < binHz * 4 + it.freq * 0.03 }) picked.add(c)
        }
        return picked
    }

    /** Parabolic-interpolated frequency of the strongest peak, or null. */
    fun dominantFrequency(mags: DoubleArray, binHz: Double, minFreq: Double = 50.0): Double? {
        if (mags.size < 4 || binHz <= 0.0) return null
        val start = maxOf(2, (minFreq / binHz).toInt())
        var best = -1
        var bestMag = 1e-6
        for (i in start until mags.size - 1) {
            if (mags[i] > bestMag) { bestMag = mags[i]; best = i }
        }
        if (best < 2) return null
        val l = mags[best - 1]
        val c = mags[best]
        val r = mags[best + 1]
        val denom = l - 2 * c + r
        val d = if (abs(denom) > 1e-12) 0.5 * (l - r) / denom else 0.0
        return (best + d.coerceIn(-0.5, 0.5)) * binHz
    }

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Nearest musical note name, e.g. "A4". */
    fun noteName(freq: Double): String {
        if (freq < 8.0) return ""
        val midi = (69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)).roundToInt()
        if (midi < 0 || midi > 127) return ""
        return NOTE_NAMES[midi % 12] + (midi / 12 - 1)
    }

    /** Note name and cents offset (-50..50) for a frequency. */
    fun noteAndCents(freq: Double): Pair<String, Double>? {
        if (freq < 8.0) return null
        val midiExact = 69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)
        val midi = midiExact.roundToInt()
        if (midi < 0 || midi > 127) return null
        val cents = (midiExact - midi) * 100.0
        return Pair(NOTE_NAMES[midi % 12] + (midi / 12 - 1), cents)
    }
}
