package com.jonathan.multitool.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.jonathan.multitool.core.dsp.FilterChain
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Decodes an audio file, runs it through the filter, saves it as WAV. */
object AudioFileProcessor {

    fun process(
        context: Context,
        uri: Uri,
        filterType: Int,
        freq: Double,
        q: Double,
        onProgress: (Float) -> Unit
    ): String {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        val fmt = format ?: throw IllegalArgumentException("No audio track found")
        extractor.selectTrack(trackIndex)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = try { fmt.getLong(MediaFormat.KEY_DURATION) } catch (t: Throwable) { 0L }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(fmt, null, null, 0)
        decoder.start()

        var chains = Array(channels) { FilterChain.build(filterType, freq, q, sampleRate.toDouble()) }
        val temp = File.createTempFile("jsa_pcm", ".raw", context.cacheDir)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var channelPhase = 0
        try {
            BufferedOutputStream(FileOutputStream(temp), 1 shl 16).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                                if (durationUs > 0) onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(info, 10000)
                    if (outIdx >= 0) {
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.asShortBuffer()
                            val arr = ShortArray(shorts.remaining())
                            shorts.get(arr)
                            val bytes = ByteArray(arr.size * 2)
                            for (i in arr.indices) {
                                var s = arr[i].toInt()
                                if (filterType != 0) {
                                    val ch = channelPhase % channels
                                    val y = chains[ch].process(s / 32768.0).coerceIn(-1.0, 1.0)
                                    s = (y * 32767.0).toInt()
                                }
                                channelPhase++
                                bytes[i * 2] = (s and 0xFF).toByte()
                                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            out.write(bytes)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = decoder.outputFormat
                        try {
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            chains = Array(channels) {
                                FilterChain.build(filterType, freq, q, sampleRate.toDouble())
                            }
                            channelPhase = 0
                        } catch (t: Throwable) { }
                    }
                }
            }
        } finally {
            try { decoder.stop() } catch (t: Throwable) { }
            decoder.release()
            extractor.release()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "JSA_clean_$stamp.wav"
        val location = WavIo.saveRawFile(context, temp, sampleRate, channels, name)
        temp.delete()
        onProgress(1f)
        return location
    }
}
