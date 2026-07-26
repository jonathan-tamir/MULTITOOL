package com.jonathan.multitool.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.jonathan.multitool.core.dsp.Biquad
import com.jonathan.multitool.core.mic.MicOwner
import com.jonathan.multitool.core.dsp.Fft
import com.jonathan.multitool.core.dsp.FilterChain
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AudioEngine {

    private val MIC_TAG = "spectrum"

    class Spectrum(
        val mags: DoubleArray,
        val binHz: Double,
        val totalPower: Double,
        val sampleRate: Int
    )

    private val _spectrum = MutableStateFlow<Spectrum?>(null)
    val spectrum: StateFlow<Spectrum?> = _spectrum

    private val _postSpectrum = MutableStateFlow<Spectrum?>(null)
    val postSpectrum: StateFlow<Spectrum?> = _postSpectrum

    private val _recordedSeconds = MutableStateFlow(0.0)
    val recordedSeconds: StateFlow<Double> = _recordedSeconds

    @Volatile var windowSeconds: Double = 0.093
    @Volatile var smoothing: Double = 0.5

    // live filter config
    @Volatile var filterType: Int = Biquad.TYPE_NONE
    @Volatile var filterFreq: Double = 1000.0
    @Volatile var filterQ: Double = 1.5
    @Volatile var monitor: Boolean = false

    private val running = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private var thread: Thread? = null
    private var smoothedRaw: DoubleArray? = null
    private var smoothedPost: DoubleArray? = null

    private val recLock = Any()
    private var recChunks: ArrayList<ShortArray>? = null
    private var recCount = 0
    private val maxRecSamples = 44100 * 300 // 5 minutes

    val isRecording: Boolean get() = recording.get()

    fun beginRecording() {
        synchronized(recLock) {
            recChunks = ArrayList()
            recCount = 0
            _recordedSeconds.value = 0.0
            recording.set(true)
        }
    }

    fun endRecording(): ShortArray {
        recording.set(false)
        synchronized(recLock) {
            val chunks = recChunks ?: return ShortArray(0)
            val out = ShortArray(recCount)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, pos, c.size)
                pos += c.size
            }
            recChunks = null
            return out
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!MicOwner.acquire(MIC_TAG)) { running.set(false); return }
        thread = Thread {
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 16384)
                )
            } catch (t: Throwable) {
                running.set(false); return@Thread
            }
            val ringSize = 1 shl 17
            val rawRing = ShortArray(ringSize)
            val postRing = ShortArray(ringSize)
            var writePos = 0L
            val chunk = ShortArray(2048)
            val filtered = ShortArray(2048)
            var chain: FilterChain? = null
            var chainKey = ""
            var track: AudioTrack? = null

            fun ensureTrack(): AudioTrack {
                val t = track
                if (t != null) return t
                val mb = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val nt = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(mb, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                nt.play()
                track = nt
                return nt
            }

            try {
                record.startRecording()
                var lastEmit = 0L
                while (running.get()) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue

                    // live filter
                    val type = filterType
                    if (type != Biquad.TYPE_NONE) {
                        val key = "$type/${filterFreq.toInt()}/${(filterQ * 10).toInt()}"
                        if (chain == null || key != chainKey) {
                            chain = FilterChain.build(type, filterFreq, filterQ, sampleRate.toDouble())
                            chainKey = key
                        }
                        val ch = chain
                        if (ch != null) {
                            for (i in 0 until n) {
                                val y = ch.process(chunk[i] / 32768.0).coerceIn(-1.0, 1.0)
                                filtered[i] = (y * 32767.0).toInt().toShort()
                            }
                        }
                    } else {
                        chain = null
                        chainKey = ""
                        System.arraycopy(chunk, 0, filtered, 0, n)
                    }

                    for (i in 0 until n) {
                        val p = (writePos % ringSize).toInt()
                        rawRing[p] = chunk[i]
                        postRing[p] = filtered[i]
                        writePos++
                    }

                    if (recording.get()) {
                        synchronized(recLock) {
                            val chunks = recChunks
                            if (chunks != null && recCount < maxRecSamples) {
                                chunks.add(filtered.copyOf(n))
                                recCount += n
                                _recordedSeconds.value = recCount / sampleRate.toDouble()
                            }
                        }
                    }

                    if (monitor) {
                        try { ensureTrack().write(filtered, 0, n) } catch (t: Throwable) { }
                    } else if (track != null) {
                        try { track?.stop() } catch (t: Throwable) { }
                        track?.release()
                        track = null
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastEmit < 50) continue
                    lastEmit = now

                    val want = (windowSeconds * sampleRate).toInt().coerceIn(512, 65536)
                    var fftSize = 512
                    while (fftSize < want) fftSize = fftSize shl 1
                    if (fftSize.toDouble() > want * 1.5) fftSize = fftSize shr 1
                    if (writePos < fftSize) continue

                    val a = smoothing.coerceIn(0.0, 0.97)
                    val startPos = writePos - fftSize

                    fun emit(ring: ShortArray, prev: DoubleArray?): Pair<Spectrum, DoubleArray> {
                        val samples = DoubleArray(fftSize)
                        for (i in 0 until fftSize) {
                            samples[i] = ring[((startPos + i) % ringSize).toInt()] / 32768.0
                        }
                        val mags = Fft.magnitudeSpectrum(samples)
                        val out = if (prev != null && prev.size == mags.size) {
                            DoubleArray(mags.size) { a * prev[it] + (1.0 - a) * mags[it] }
                        } else mags
                        var total = 0.0
                        for (i in 1 until out.size) total += out[i] * out[i]
                        return Pair(Spectrum(out, sampleRate.toDouble() / fftSize, total, sampleRate), out)
                    }

                    val rawResult = emit(rawRing, smoothedRaw)
                    smoothedRaw = rawResult.second
                    _spectrum.value = rawResult.first

                    if (type != Biquad.TYPE_NONE) {
                        val postResult = emit(postRing, smoothedPost)
                        smoothedPost = postResult.second
                        _postSpectrum.value = postResult.first
                    } else {
                        smoothedPost = null
                        _postSpectrum.value = null
                    }
                }
            } catch (t: Throwable) {
                // stop silently
            } finally {
                try { record.stop() } catch (t: Throwable) { }
                record.release()
                try { track?.stop() } catch (t: Throwable) { }
                track?.release()
            }
        }
        thread?.start()
    }

    fun stop() {
        running.set(false)
        MicOwner.release(MIC_TAG)
        try { thread?.join(500) } catch (t: Throwable) { }
        thread = null
        smoothedRaw = null
        smoothedPost = null
    }
}
