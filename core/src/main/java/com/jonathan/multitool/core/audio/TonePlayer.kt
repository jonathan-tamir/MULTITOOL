package com.jonathan.multitool.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class TonePlayer {
    @Volatile var freq: Double = 440.0
    @Volatile var amplitude: Double = 0.4

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            val sr = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var phase = 0.0
                val buf = ShortArray(2048)
                while (running.get()) {
                    val step = 2.0 * PI * freq / sr
                    val amp = amplitude.coerceIn(0.0, 1.0) * 32000.0
                    for (i in buf.indices) {
                        buf[i] = (sin(phase) * amp).toInt().toShort()
                        phase += step
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                    }
                    track.write(buf, 0, buf.size)
                }
            } catch (t: Throwable) {
                // give up silently
            } finally {
                try { track.stop() } catch (t: Throwable) { }
                track.release()
            }
        }
        thread?.start()
    }

    fun stop() {
        running.set(false)
        try { thread?.join(400) } catch (t: Throwable) { }
        thread = null
    }
}
