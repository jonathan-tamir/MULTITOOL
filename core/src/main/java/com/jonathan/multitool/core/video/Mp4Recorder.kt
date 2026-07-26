package com.jonathan.multitool.core.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.jonathan.multitool.core.media.MediaSave
import java.io.File

/** Encodes a stream of bitmaps into an H.264 MP4 (grayscale frames). */
class Mp4Recorder(private val context: Context, private val size: Int) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var startNs = -1L
    private var lastPts = -1L
    private val info = MediaCodec.BufferInfo()
    private var temp: File? = null
    private val pixels = IntArray(size * size)

    @Volatile var active = false
        private set

    @Synchronized
    fun start() {
        if (active) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size, size)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        val t = File(context.cacheDir, "jsa_rec_${System.nanoTime()}.mp4")
        temp = t
        muxer = MediaMuxer(t.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        startNs = -1L
        lastPts = -1L
        active = true
    }

    /** Encode one frame; [bmp] is scaled to the recorder size. Safe to call from any thread. */
    @Synchronized
    fun encodeFrame(bmp: Bitmap) {
        if (!active) return
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10000)
            if (inIdx < 0) { drain(false); return }
            val image = c.getInputImage(inIdx)
            if (image == null) { c.queueInputBuffer(inIdx, 0, 0, 0, 0); return }
            val scaled = if (bmp.width != size || bmp.height != size) {
                Bitmap.createScaledBitmap(bmp, size, size, true)
            } else bmp
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)

            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yStride = yPlane.rowStride
            val yPix = yPlane.pixelStride
            for (row in 0 until size) {
                val base = row * yStride
                val po = row * size
                for (col in 0 until size) {
                    val p = pixels[po + col]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                    yBuf.put(base + col * yPix, y.toByte())
                }
            }
            for (pi in 1..2) {
                val plane = image.planes[pi]
                val buf = plane.buffer
                val stride = plane.rowStride
                val pix = plane.pixelStride
                val half = size / 2
                for (row in 0 until half) {
                    val base = row * stride
                    for (col in 0 until half) {
                        val idx = base + col * pix
                        if (idx < buf.limit()) buf.put(idx, 128.toByte())
                    }
                }
            }
            val now = System.nanoTime()
            if (startNs < 0) startNs = now
            var pts = (now - startNs) / 1000
            if (pts <= lastPts) pts = lastPts + 1
            lastPts = pts
            c.queueInputBuffer(inIdx, 0, size * size * 3 / 2, pts, 0)
            drain(false)
        } catch (t: Throwable) {
            // drop frame
        }
    }

    /** Stops, publishes to the gallery, returns the saved location (or null). */
    @Synchronized
    fun stop(): String? {
        if (!active) return null
        active = false
        val c = codec ?: return null
        var location: String? = null
        try {
            val inIdx = c.dequeueInputBuffer(20000)
            if (inIdx >= 0) {
                c.queueInputBuffer(inIdx, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (t: Throwable) { }
        try { c.stop() } catch (t: Throwable) { }
        c.release()
        codec = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (t: Throwable) { }
        try { muxer?.release() } catch (t: Throwable) { }
        muxer = null
        val t = temp
        if (t != null && t.length() > 0 && muxerStarted) {
            location = try {
                MediaSave.publishFile(context, t, "JSA_video_${System.currentTimeMillis()}.mp4", "video")
            } catch (e: Throwable) { null }
        }
        t?.delete()
        temp = null
        return location
    }

    private fun drain(end: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        var spins = 0
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, if (end) 10000 else 0)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = m.addTrack(c.outputFormat)
                m.start()
                muxerStarted = true
            } else if (outIdx >= 0) {
                val buf = c.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0 && muxerStarted &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    m.writeSampleData(trackIndex, buf, info)
                }
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else {
                if (!end) return
                spins++
                if (spins > 100) return
            }
        }
    }
}
