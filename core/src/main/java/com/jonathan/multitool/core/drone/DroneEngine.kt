package com.jonathan.multitool.core.drone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.jonathan.multitool.core.mic.MicOwner
import kotlin.concurrent.thread

/** Captures the mic at 16 kHz mono and, every HOP seconds, scores the most recent
 *  1 s window with the featurizer + model. Emits (probability, isDetection) on a
 *  background thread; the caller marshals to the UI. */
class DroneEngine(
    private val model: DroneModel,
    private val onResult: (prob: Float, detection: Boolean) -> Unit
) {
    private val MIC_TAG = "drone"
    private val sr = 16000
    private val winLen = sr            // 1 s
    private val hopLen = sr / 2        // 0.5 s -> 50% overlap
    private val featurizer = Featurizer()

    // temporal consensus: require K of the last N windows above threshold
    private val N = 4
    private val K = 3
    private val recent = ArrayDeque<Boolean>()

    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!MicOwner.acquire(MIC_TAG)) { onResult(0f, false); return }
        running = true
        worker = thread(name = "audio-engine") {
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, winLen * 2)
            )
            val ring = FloatArray(winLen)   // circular 1 s buffer
            var filled = 0; var head = 0
            val chunk = ShortArray(hopLen)
            recorder.startRecording()
            try {
                while (running) {
                    var got = 0
                    while (got < chunk.size && running) {
                        val r = recorder.read(chunk, got, chunk.size - got)
                        if (r <= 0) break
                        got += r
                    }
                    for (i in 0 until got) {
                        ring[head] = chunk[i] / 32768f
                        head = (head + 1) % winLen
                        if (filled < winLen) filled++
                    }
                    if (filled >= winLen) {
                        val ordered = FloatArray(winLen)
                        for (i in 0 until winLen) ordered[i] = ring[(head + i) % winLen]
                        val feat = featurizer.extract(ordered)
                        val p = model.predict(feat)
                        val above = p >= model.threshold
                        recent.addLast(above)
                        while (recent.size > N) recent.removeFirst()
                        val detection = recent.count { it } >= K
                        onResult(p, detection)
                    }
                }
            } finally {
                recorder.stop(); recorder.release()
            }
        }
    }

    fun stop() {
        running = false
        MicOwner.release(MIC_TAG)
        worker?.join(500)
        worker = null
        recent.clear()
    }
}
