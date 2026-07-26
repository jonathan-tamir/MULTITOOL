package com.jonathan.multitool.core.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.jonathan.multitool.core.dsp.Biquad
import com.jonathan.multitool.core.dsp.Fft
import com.jonathan.multitool.core.dsp.FilterChain
import com.jonathan.multitool.core.image.ImageMath
import com.jonathan.multitool.core.media.MediaSave
import java.io.File
import java.nio.ByteBuffer

/** Re-encodes a video with a spatial FFT filter or global de-flicker. Audio is copied through. */
object VideoTransformer {

    const val MODE_LOWPASS = 1
    const val MODE_HIGHPASS = 2
    const val MODE_DEFLICKER = 3

    fun transform(
        context: Context,
        uri: Uri,
        mode: Int,
        cutoffFrac: Double,
        flickerHz: Double,
        onProgress: (Float) -> Unit
    ): String {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(context, uri, null)
        var vIdx = -1
        var vFormat: MediaFormat? = null
        for (i in 0 until videoExtractor.trackCount) {
            val f = videoExtractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                vIdx = i; vFormat = f; break
            }
        }
        val vf = vFormat ?: throw IllegalArgumentException("No video track found")
        videoExtractor.selectTrack(vIdx)
        val mime = vf.getString(MediaFormat.KEY_MIME)!!
        val width = vf.getInteger(MediaFormat.KEY_WIDTH)
        val height = vf.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = try { vf.getLong(MediaFormat.KEY_DURATION) } catch (t: Throwable) { 0L }
        val rotation = try { vf.getInteger("rotation-degrees") } catch (t: Throwable) { 0 }
        val fps = try { vf.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (t: Throwable) { 30 }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(vf, null, null, 0)
        decoder.start()

        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        encFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        encFormat.setInteger(
            MediaFormat.KEY_BIT_RATE,
            (width * height * 4).coerceIn(2_000_000, 12_000_000)
        )
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(10, 60))
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val temp = File(context.cacheDir, "jsa_transform_${System.nanoTime()}.mp4")
        val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(context, uri, null)
        var aIdx = -1
        var aFormat: MediaFormat? = null
        for (i in 0 until audioExtractor.trackCount) {
            val f = audioExtractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                aIdx = i; aFormat = f; break
            }
        }
        var audioTrack = -1
        if (aIdx >= 0 && aFormat != null) audioTrack = muxer.addTrack(aFormat)

        var videoTrack = -1
        var muxerStarted = false
        val encInfo = MediaCodec.BufferInfo()
        val decInfo = MediaCodec.BufferInfo()

        // spatial filter working grid
        val m = if (minOf(width, height) < 256) 128 else 256
        val maxR = m / 2.0
        val mask: DoubleArray? = when (mode) {
            MODE_LOWPASS -> ImageMath.lowpassMask(m, (cutoffFrac * maxR).coerceAtLeast(1.0), 0.02 * maxR)
            MODE_HIGHPASS -> ImageMath.highpassMask(m, (cutoffFrac * maxR).coerceAtLeast(1.0), 0.02 * maxR)
            else -> null
        }
        val deflicker = FilterChain.build(Biquad.TYPE_NOTCH, flickerHz, 2.0, fps.toDouble().coerceAtLeast(10.0))
        val grid = DoubleArray(m * m)
        val gridIm = DoubleArray(m * m)

        fun drainEncoder(end: Boolean) {
            var spins = 0
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(encInfo, if (end) 10000 else 0)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (buf != null && encInfo.size > 0 && muxerStarted &&
                        (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(videoTrack, buf, encInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                } else {
                    if (!end) return
                    spins++
                    if (spins > 200) return
                }
            }
        }

        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = videoExtractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10000)
                if (outIdx >= 0) {
                    val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (decInfo.size > 0) {
                        val imgIn = decoder.getOutputImage(outIdx)
                        if (imgIn != null) {
                            var encIn = -1
                            var guard = 0
                            while (encIn < 0 && guard < 100) {
                                drainEncoder(false)
                                encIn = encoder.dequeueInputBuffer(10000)
                                guard++
                            }
                            if (encIn >= 0) {
                                val imgOut = encoder.getInputImage(encIn)
                                if (imgOut != null) {
                                    processFrame(imgIn, imgOut, width, height, mode, mask, m, grid, gridIm, deflicker)
                                    encoder.queueInputBuffer(
                                        encIn, 0, width * height * 3 / 2, decInfo.presentationTimeUs, 0
                                    )
                                } else {
                                    encoder.queueInputBuffer(encIn, 0, 0, 0, 0)
                                }
                            }
                        }
                        if (durationUs > 0) {
                            onProgress((decInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 0.95f))
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) {
                        outputDone = true
                        var encIn = -1
                        var guard = 0
                        while (encIn < 0 && guard < 100) {
                            drainEncoder(false)
                            encIn = encoder.dequeueInputBuffer(10000)
                            guard++
                        }
                        if (encIn >= 0) {
                            encoder.queueInputBuffer(
                                encIn, 0, 0, decInfo.presentationTimeUs + 1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                        drainEncoder(true)
                    }
                }
            }

            // audio passthrough
            if (audioTrack >= 0 && muxerStarted) {
                audioExtractor.selectTrack(aIdx)
                val abuf = ByteBuffer.allocate(1 shl 20)
                val ainfo = MediaCodec.BufferInfo()
                while (true) {
                    val size = audioExtractor.readSampleData(abuf, 0)
                    if (size < 0) break
                    val flags = if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    ainfo.set(0, size, audioExtractor.sampleTime, flags)
                    muxer.writeSampleData(audioTrack, abuf, ainfo)
                    audioExtractor.advance()
                }
            }
        } finally {
            try { decoder.stop() } catch (t: Throwable) { }
            decoder.release()
            try { encoder.stop() } catch (t: Throwable) { }
            encoder.release()
            try { if (muxerStarted) muxer.stop() } catch (t: Throwable) { }
            try { muxer.release() } catch (t: Throwable) { }
            videoExtractor.release()
            audioExtractor.release()
        }

        if (!muxerStarted || temp.length() == 0L) {
            temp.delete()
            throw IllegalStateException("Transform produced no output")
        }
        val location = MediaSave.publishFile(
            context, temp, "JSA_transform_${System.currentTimeMillis()}.mp4", "video"
        )
        temp.delete()
        onProgress(1f)
        return location
    }

    private fun processFrame(
        imgIn: android.media.Image,
        imgOut: android.media.Image,
        width: Int,
        height: Int,
        mode: Int,
        mask: DoubleArray?,
        m: Int,
        grid: DoubleArray,
        gridIm: DoubleArray,
        deflicker: FilterChain
    ) {
        val yIn = imgIn.planes[0]
        val yInBuf = yIn.buffer
        val yInStride = yIn.rowStride
        val yInPix = yIn.pixelStride
        val w = minOf(width, imgIn.width)
        val h = minOf(height, imgIn.height)

        val yOut = imgOut.planes[0]
        val yOutBuf = yOut.buffer
        val yOutStride = yOut.rowStride
        val yOutPix = yOut.pixelStride

        if (mode == MODE_DEFLICKER) {
            var sum = 0.0
            var count = 0
            var yy = 0
            while (yy < h) {
                val base = yy * yInStride
                var xx = 0
                while (xx < w) {
                    sum += (yInBuf.get(base + xx * yInPix).toInt() and 0xFF)
                    count++
                    xx += 4
                }
                yy += 4
            }
            val mean = if (count > 0) sum / count else 128.0
            val target = deflicker.process(mean)
            val gain = if (mean > 1.0) (target / mean).coerceIn(0.6, 1.6) else 1.0
            for (row in 0 until h) {
                val inBase = row * yInStride
                val outBase = row * yOutStride
                for (col in 0 until w) {
                    val v = (yInBuf.get(inBase + col * yInPix).toInt() and 0xFF)
                    val o = (v * gain).toInt().coerceIn(0, 255)
                    yOutBuf.put(outBase + col * yOutPix, o.toByte())
                }
            }
        } else {
            // sample Y to m x m
            for (gy in 0 until m) {
                val sy = gy * h / m
                val base = sy * yInStride
                for (gx in 0 until m) {
                    val sx = gx * w / m
                    grid[gy * m + gx] = (yInBuf.get(base + sx * yInPix).toInt() and 0xFF) / 255.0
                    gridIm[gy * m + gx] = 0.0
                }
            }
            Fft.fft2(grid, gridIm, m, m)
            if (mask != null) {
                for (i in grid.indices) {
                    grid[i] *= mask[i]
                    gridIm[i] *= mask[i]
                }
            }
            Fft.ifft2(grid, gridIm, m, m)
            val highpass = mode == MODE_HIGHPASS
            for (row in 0 until h) {
                val gy = row * m / h
                val gBase = gy * m
                val outBase = row * yOutStride
                for (col in 0 until w) {
                    val v = grid[gBase + col * m / w]
                    val o = if (highpass) (128.0 + v * 510.0).toInt().coerceIn(0, 255)
                    else (v * 255.0).toInt().coerceIn(0, 255)
                    yOutBuf.put(outBase + col * yOutPix, o.toByte())
                }
            }
        }

        // chroma: copy for LP/de-flicker, neutral gray for HP
        val gray = mode == MODE_HIGHPASS
        for (pi in 1..2) {
            val pIn = imgIn.planes[pi]
            val pOut = imgOut.planes[pi]
            val inBuf = pIn.buffer
            val outBuf = pOut.buffer
            val ch = (h + 1) / 2
            val cw = (w + 1) / 2
            for (row in 0 until ch) {
                val inBase = row * pIn.rowStride
                val outBase = row * pOut.rowStride
                for (col in 0 until cw) {
                    val outIdx = outBase + col * pOut.pixelStride
                    if (outIdx >= outBuf.limit()) continue
                    val value: Byte = if (gray) 128.toByte() else {
                        val inIdx = inBase + col * pIn.pixelStride
                        if (inIdx < inBuf.limit()) inBuf.get(inIdx) else 128.toByte()
                    }
                    outBuf.put(outIdx, value)
                }
            }
        }
    }
}
