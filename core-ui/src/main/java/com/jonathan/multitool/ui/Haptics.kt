package com.jonathan.multitool.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic haptics. Compose's own HapticFeedback offers two vague constants, which is why the
 * shell felt flat — everything got the same faint tick. These are real waveforms with amplitudes,
 * chosen so different actions feel different under the thumb: a tool launching should feel like a
 * mechanism engaging, not like a button.
 */
class Haptics(private val vib: Vibrator?, private val enabled: () -> Boolean) {

    private fun fire(timings: LongArray, amplitudes: IntArray) {
        if (!enabled()) return
        val v = vib ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (t: Throwable) {
            // some devices reject amplitude control
        }
    }

    private fun one(ms: Long, amp: Int) = fire(longArrayOf(0, ms), intArrayOf(0, amp))

    /** Smallest confirmation — drawer, scrim, list rows. */
    fun tap() = one(12, 110)

    /** Something was chosen and the view is changing. */
    fun select() = one(18, 170)

    /** Back / dismissal: softer and shorter than select, so it reads as undoing. */
    fun back() = one(10, 90)

    /** A switch moved. Rising double for on, single thud for off. */
    fun toggle(on: Boolean) =
        if (on) fire(longArrayOf(0, 10, 25, 18), intArrayOf(0, 110, 0, 220))
        else one(14, 120)

    /** Tool takeover — a three-stage mechanism engaging, timed against the ignition animation. */
    fun launch() = fire(
        longArrayOf(0, 16, 45, 28, 60, 18),
        intArrayOf(0, 150, 0, 255, 0, 120)
    )

    /** Transmission started / data on the wire. */
    fun emit() = fire(longArrayOf(0, 12, 20, 12), intArrayOf(0, 180, 0, 180))

    /** Something was detected. Deliberately hard to ignore. */
    fun alert() = fire(
        longArrayOf(0, 45, 70, 45, 70, 70),
        intArrayOf(0, 255, 0, 255, 0, 255)
    )

    /** Continuous adjustment passing a notch. */
    fun tick() = one(6, 70)
}

val LocalHaptics = staticCompositionLocalOf { Haptics(null) { false } }

@Composable
fun rememberHaptics(enabled: () -> Boolean): Haptics {
    val context = LocalContext.current
    return remember(context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        Haptics(v, enabled)
    }
}
