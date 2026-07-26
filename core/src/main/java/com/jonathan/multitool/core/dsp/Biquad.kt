package com.jonathan.multitool.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RBJ audio-cookbook biquad filter. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_LOWPASS = 1
        const val TYPE_HIGHPASS = 2
        const val TYPE_BANDPASS = 3
        const val TYPE_NOTCH = 4

        /** Raw normalized coefficients [b0, b1, b2, a1, a2]. */
        fun coeffs(type: Int, f0: Double, q: Double, sampleRate: Double): DoubleArray {
            val freq = f0.coerceIn(0.01, sampleRate * 0.49)
            val w0 = 2.0 * PI * freq / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * q.coerceAtLeast(0.05))
            val b0: Double
            val b1: Double
            val b2: Double
            when (type) {
                TYPE_LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
                TYPE_HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
                TYPE_BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
                TYPE_NOTCH -> { b0 = 1.0; b1 = -2 * cw; b2 = 1.0 }
                else -> { b0 = 1.0; b1 = 0.0; b2 = 0.0 }
            }
            val a0 = 1 + alpha
            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, (-2 * cw) / a0, (1 - alpha) / a0)
        }

        fun design(type: Int, f0: Double, q: Double, sampleRate: Double): Biquad {
            val freq = f0.coerceIn(1.0, sampleRate * 0.49)
            val w0 = 2.0 * PI * freq / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * q.coerceAtLeast(0.05))
            val b0: Double
            val b1: Double
            val b2: Double
            when (type) {
                TYPE_LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
                TYPE_HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
                TYPE_BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
                TYPE_NOTCH -> { b0 = 1.0; b1 = -2 * cw; b2 = 1.0 }
                else -> { b0 = 1.0; b1 = 0.0; b2 = 0.0 }
            }
            val a0 = 1 + alpha
            val a1 = -2 * cw
            val a2 = 1 - alpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/** A cascade of biquads (steeper response). */
class FilterChain(private val stages: List<Biquad>) {

    fun process(x: Double): Double {
        var v = x
        for (s in stages) v = s.process(v)
        return v
    }

    fun reset() { for (s in stages) s.reset() }

    companion object {
        /** Two cascaded stages of the requested filter. */
        fun build(type: Int, f0: Double, q: Double, sampleRate: Double): FilterChain =
            FilterChain(listOf(
                Biquad.design(type, f0, q, sampleRate),
                Biquad.design(type, f0, q, sampleRate)
            ))
    }
}
