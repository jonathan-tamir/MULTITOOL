package com.jonathan.multitool.core.drone

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Exact Kotlin mirror of ml/features.py (portable definition).
 *  1s @ 16kHz float [-1,1] -> 192-dim pooled log-mel feature vector. */
class Featurizer(
    private val sr: Int = 16000,
    private val nFft: Int = 512,
    private val hop: Int = 256,
    private val nMels: Int = 64,
    private val fMin: Float = 50f,
    private val fMax: Float = 8000f
) {
    private val fft = Fft(nFft)
    private val hann = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / (nFft - 1))).toFloat() }
    private val fb: Array<FloatArray> = melFilterbank()   // (nMels, nFft/2+1)
    private val nBins = nFft / 2 + 1

    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

    private fun melFilterbank(): Array<FloatArray> {
        val bins = IntArray(nMels + 2)
        val melLo = hzToMel(fMin.toDouble()); val melHi = hzToMel(fMax.toDouble())
        for (m in 0 until nMels + 2) {
            val mel = melLo + (melHi - melLo) * m / (nMels + 1)
            bins[m] = Math.floor((nFft + 1) * melToHz(mel) / sr).toInt()
        }
        val out = Array(nMels) { FloatArray(nFft / 2 + 1) }
        for (m in 1..nMels) {
            var l = bins[m - 1]; var c = bins[m]; var r = bins[m + 1]
            if (c == l) c += 1
            if (r == c) r += 1
            for (k in l until c) if (k in 0 until out[0].size) out[m - 1][k] = (k - l).toFloat() / maxOf(c - l, 1)
            for (k in c until r) if (k in 0 until out[0].size) out[m - 1][k] = (r - k).toFloat() / maxOf(r - c, 1)
        }
        return out
    }

    /** waveform length == sr (1s). Returns 192 features: mean|std|max per mel band. */
    fun extract(wav: FloatArray): FloatArray {
        val x = FloatArray(sr)
        System.arraycopy(wav, 0, x, 0, minOf(wav.size, sr))
        val power = FloatArray(nBins)
        val frame = FloatArray(nFft)
        var t = 0
        val sum = FloatArray(nMels); val sumSq = FloatArray(nMels)
        val mx = FloatArray(nMels) { Float.NEGATIVE_INFINITY }
        var start = 0
        while (start + nFft <= sr) {
            for (i in 0 until nFft) frame[i] = x[start + i] * hann[i]
            fft.powerSpectrum(frame, power)
            for (m in 0 until nMels) {
                var acc = 0f
                val row = fb[m]
                for (k in 0 until nBins) acc += row[k] * power[k]
                val lm = ln(acc + 1e-6f)
                sum[m] += lm; sumSq[m] += lm * lm
                if (lm > mx[m]) mx[m] = lm
            }
            t += 1; start += hop
        }
        val feat = FloatArray(nMels * 3)
        for (m in 0 until nMels) {
            val mean = sum[m] / t
            val varr = maxOf(sumSq[m] / t - mean * mean, 0f)
            feat[m] = mean
            feat[nMels + m] = sqrt(varr)
            feat[2 * nMels + m] = mx[m]
        }
        return feat
    }
}
