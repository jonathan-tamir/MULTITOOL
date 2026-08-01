package com.jonathan.multitool.feature.comms

import androidx.camera.core.CameraControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How transmit and receive share the light. */
enum class Duplex { FULL, TDD, AUTO }

data class LinkStats(
    val symbolMs: Long = 150,
    val latencyMs: Double = Double.NaN,
    val jitterMs: Double = Double.NaN,
    val echoGain: Double = 0.0,
    val echoResidual: Double = 0.0,
    val contrast: Double = 0.0,
    val fps: Double = 0.0,
    val duplexActive: Duplex = Duplex.FULL,
    val calibrated: Boolean = false
)

/**
 * The physical layer: torch out, camera in, on one device at the same time.
 *
 * Transmit is a thread walking absolute deadlines (`t0 + n × period`) so the *mean* symbol period
 * stays exactly nominal no matter how long the HAL takes on any individual edge — only jitter is
 * left, and jitter does not accumulate.
 *
 * Receive is fed one luminance pair per camera frame: a tight centre ROI (where the far torch is
 * aimed) and the annulus around it. Subtracting them removes most of our own diffuse backscatter
 * before any signal processing, because our echo is spread over the whole frame while the remote
 * torch is a concentrated spot. Whatever echo survives is removed adaptively: we know our own
 * transmitted waveform exactly, so an NLMS loop estimates its gain and subtracts it — the remote
 * data is uncorrelated with our own bits, so it averages out of the estimate instead of being
 * cancelled with the echo.
 *
 * Output is a stream of runs (level, duration), which is all three codecs need.
 */
class TorchLink(
    symbolMs: Long = 150L,
    var duplexMode: Duplex = Duplex.AUTO
) {
    /**
     * Mirror loopback: the return *is* our own transmission, so echo cancellation would delete
     * exactly the signal we want. Off in loopback, along with the annulus subtraction and the
     * take-turns fallback (which would mute the slot the reflection arrives in).
     */
    @Volatile var loopback = false

    @Volatile var control: CameraControl? = null
    @Volatile var onRun: ((Boolean, Long) -> Unit)? = null
    @Volatile var onLog: ((String) -> Unit)? = null

    @Volatile var symbolMs: Long = symbolMs
        set(v) { field = v.coerceIn(40L, 2000L); _stats.value = _stats.value.copy(symbolMs = field) }

    private val _stats = MutableStateFlow(LinkStats(symbolMs = symbolMs))
    val stats: StateFlow<LinkStats> = _stats

    /** Recent signal samples for the UI trace: residual after echo removal, normalised. */
    private val _trace = MutableStateFlow(FloatArray(0))
    val trace: StateFlow<FloatArray> = _trace

    private val _txActive = MutableStateFlow(false)
    val txActive: StateFlow<Boolean> = _txActive

    // ── transmit ──────────────────────────────────────────────────────────────
    private val txQueue = ArrayDeque<Boolean>()
    private val txLock = Any()
    private val running = AtomicBoolean(false)
    private var txThread: Thread? = null

    /** What the torch was asked to do, and when. Read by the echo canceller. */
    @Volatile private var txState = false
    @Volatile private var txChangedAt = 0L

    private val traceBuf = ArrayDeque<Float>()

    fun enqueue(bits: List<Boolean>) {
        synchronized(txLock) {
            // a dark guard slot keeps two back-to-back messages from merging into one run
            txQueue.addAll(bits)
            txQueue.addAll(listOf(false, false))
        }
    }

    fun pendingSymbols(): Int = synchronized(txLock) { txQueue.size }

    fun clearQueue() = synchronized(txLock) { txQueue.clear() }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        txThread = thread(name = "torch-tx", isDaemon = true) {
            var slot = 0L
            val t0 = System.nanoTime()
            while (running.get()) {
                val periodNs = symbolMs * 1_000_000L
                val deadline = t0 + slot * periodNs
                val wait = deadline - System.nanoTime()
                if (wait > 0) {
                    try { Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) }
                    catch (t: InterruptedException) { break }
                }
                // In TDD every other slot is silent so the far end has clear air to answer in.
                val listenSlot = _stats.value.duplexActive == Duplex.TDD && (slot % 2L == 1L)
                val bit = if (listenSlot) false else synchronized(txLock) {
                    if (txQueue.isEmpty()) false else txQueue.removeFirst()
                }
                setTorch(bit)
                _txActive.value = synchronized(txLock) { txQueue.isNotEmpty() }
                slot++
            }
            setTorch(false)
        }
    }

    fun stop() {
        running.set(false)
        txThread?.interrupt()
        txThread = null
        setTorch(false)
    }

    private fun setTorch(on: Boolean) {
        if (on != txState) {
            txState = on
            txChangedAt = System.nanoTime()
        }
        try { control?.enableTorch(on) } catch (t: Throwable) { /* camera closing */ }
    }

    // ── receive ───────────────────────────────────────────────────────────────
    private var echoGain = 0.0          // NLMS estimate of our own light's contribution
    private var lo = 0.0                // envelope trackers for the slicer
    private var hi = 0.0
    private var envInit = false
    private var level = false
    private var lastEdgeNs = 0L
    private var lastFrameNs = 0L
    private var fpsEst = 0.0
    private var echoErrAcc = 0.0
    private var echoRefAcc = 0.0

    // calibration state
    private var calibrating = false
    private val calSignal = ArrayList<Pair<Long, Double>>()
    private var calStartNs = 0L

    /**
     * One camera frame. [roi] is the mean luminance of the aiming box, [ring] the mean of the
     * annulus around it, both 0..1.
     */
    fun onFrame(roi: Double, ring: Double, tNanos: Long) {
        if (lastFrameNs != 0L) {
            val dt = (tNanos - lastFrameNs) / 1e9
            if (dt > 0.002 && dt < 1.0) fpsEst = if (fpsEst == 0.0) 1 / dt else 0.9 * fpsEst + 0.1 / dt
        }
        lastFrameNs = tNanos

        // spatial isolation: our backscatter lifts the whole frame, the far torch lifts only the ROI.
        // In loopback the reflection can fill the frame, so read the box raw and let the slicer's
        // envelope tracking handle the baseline instead.
        val spatial = if (loopback) roi else roi - ring

        if (calibrating) {
            calSignal.add(tNanos to spatial)
            return
        }

        // adaptive echo removal against our own known waveform
        val ref = if (txState) 1.0 else 0.0
        val residual = if (loopback) spatial else spatial - echoGain * ref
        if (ref != 0.0 && !loopback) {
            val mu = 0.02
            echoGain += mu * residual * ref / (ref * ref + 1e-6)
            echoErrAcc = 0.98 * echoErrAcc + 0.02 * abs(residual)
            echoRefAcc = 0.98 * echoRefAcc + 0.02 * abs(spatial)
        }

        slice(residual, tNanos)

        traceBuf.addLast(residual.toFloat())
        while (traceBuf.size > 240) traceBuf.removeFirst()
        _trace.value = traceBuf.toFloatArray()

        val contrast = hi - lo
        val resid = if (echoRefAcc > 1e-9) (echoErrAcc / echoRefAcc) else 0.0
        var active = _stats.value.duplexActive
        if (loopback) {
            active = Duplex.FULL
        } else if (duplexMode == Duplex.AUTO) {
            // If what's left after cancellation is still dominated by our own light, stop trying to
            // listen while we talk and take turns instead.
            val poor = echoGain > 0.02 && resid > 0.65
            val next = if (poor) Duplex.TDD else Duplex.FULL
            if (next != active) {
                active = next
                onLog?.invoke(
                    if (next == Duplex.TDD) "echo too strong — falling back to take-turns"
                    else "echo cancelled cleanly — full duplex"
                )
            }
        } else active = duplexMode

        _stats.value = _stats.value.copy(
            echoGain = echoGain, echoResidual = resid, contrast = contrast,
            fps = fpsEst, duplexActive = active
        )
    }

    /** Envelope-tracking Schmitt trigger; emits (level, duration) runs on each transition. */
    private fun slice(x: Double, tNanos: Long) {
        if (!envInit) { lo = x; hi = x + 0.01; envInit = true; lastEdgeNs = tNanos; return }
        // fast to follow a new extreme, slow to forget one
        if (x < lo) lo = x else lo += (x - lo) * 0.002
        if (x > hi) hi = x else hi += (x - hi) * 0.002

        val span = hi - lo
        if (span < MIN_CONTRAST) return           // nothing worth calling a signal
        val hiThr = lo + span * 0.60
        val loThr = lo + span * 0.40

        val next = when {
            !level && x > hiThr -> true
            level && x < loThr -> false
            else -> level
        }
        if (next != level) {
            val dur = tNanos - lastEdgeNs
            if (lastEdgeNs != 0L && dur > 0) onRun?.invoke(level, dur)
            level = next
            lastEdgeNs = tNanos
        }
    }

    /** Nudge the receiver back to a clean slate (aim changed, lights changed). */
    fun resetReceiver() {
        envInit = false; level = false; lastEdgeNs = 0L
        echoGain = 0.0; echoErrAcc = 0.0; echoRefAcc = 0.0
        traceBuf.clear()
    }

    // ── self-calibration ──────────────────────────────────────────────────────
    /**
     * Because the camera sees our own torch, the phone can measure its own actuation latency and
     * jitter without a second device: flash a known pattern, correlate the captured brightness
     * against the schedule, and take the lag of the peak. That gives the real symbol floor for this
     * handset instead of an assumed one.
     */
    fun calibrate(onDone: (Double, Double, Double) -> Unit) {
        thread(name = "torch-cal", isDaemon = true) {
            val period = 250L
            val pulses = 8
            calSignal.clear()
            calibrating = true
            calStartNs = System.nanoTime()
            val edges = ArrayList<Long>()
            try {
                for (i in 0 until pulses) {
                    val on = System.nanoTime()
                    setTorch(true); edges.add(on)
                    Thread.sleep(period)
                    setTorch(false)
                    Thread.sleep(period)
                }
                Thread.sleep(300)
            } catch (t: InterruptedException) {
                calibrating = false; return@thread
            }
            calibrating = false
            val samples = ArrayList(calSignal)
            if (samples.size < 8) { onDone(Double.NaN, Double.NaN, 0.0); return@thread }

            // expected waveform at lag L, correlated against what we actually saw
            fun corrAt(lagMs: Long): Double {
                var num = 0.0; var n = 0
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0
                for ((t, v) in samples) {
                    val rel = (t - calStartNs) / 1_000_000L - lagMs
                    if (rel < 0 || rel > pulses * 2 * period) continue
                    val expect = if ((rel / period) % 2L == 0L) 1.0 else 0.0
                    sx += expect; sy += v; sxx += expect * expect; syy += v * v
                    num += expect * v; n++
                }
                if (n < 4) return 0.0
                val cov = num - sx * sy / n
                val vx = sxx - sx * sx / n
                val vy = syy - sy * sy / n
                return if (vx > 1e-12 && vy > 1e-12) cov / sqrt(vx * vy) else 0.0
            }

            var bestLag = 0L; var best = -2.0
            var lag = 0L
            while (lag <= 300L) {
                val c = corrAt(lag)
                if (c > best) { best = c; bestLag = lag }
                lag += 5L
            }

            // amplitude of our own echo, and how tightly the observed edges land
            var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
            for ((_, v) in samples) { mn = min(mn, v); mx = max(mx, v) }
            val amp = if (mx > mn) mx - mn else 0.0

            val jitter = edgeSpread(samples, bestLag, period, pulses)
            onDone(bestLag.toDouble(), jitter, amp)
            _stats.value = _stats.value.copy(
                latencyMs = bestLag.toDouble(), jitterMs = jitter, calibrated = true
            )
        }
    }

    /** Std-dev of observed transition times against the schedule, in ms. */
    private fun edgeSpread(
        samples: List<Pair<Long, Double>>, lagMs: Long, period: Long, pulses: Int
    ): Double {
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        for ((_, v) in samples) { mn = min(mn, v); mx = max(mx, v) }
        if (mx - mn < 1e-4) return Double.NaN
        val mid = (mn + mx) / 2
        val errs = ArrayList<Double>()
        var prev: Boolean? = null
        var prevT = 0L
        for ((t, v) in samples) {
            val b = v > mid
            if (prev != null && b != prev) {
                val cross = (t + prevT) / 2
                val rel = (cross - calStartNs) / 1_000_000L - lagMs
                val nearest = ((rel.toDouble() / period).roundToInt()) * period
                if (nearest in 0..(pulses * 2 * period)) errs.add((rel - nearest).toDouble())
            }
            prev = b; prevT = t
        }
        if (errs.size < 3) return Double.NaN
        val mean = errs.average()
        return sqrt(errs.sumOf { (it - mean) * (it - mean) } / errs.size)
    }

    companion object {
        /** Below this ROI-minus-ring swing there is no light to talk about. */
        const val MIN_CONTRAST = 0.012
    }
}
