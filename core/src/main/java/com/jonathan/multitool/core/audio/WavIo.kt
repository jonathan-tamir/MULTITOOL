package com.jonathan.multitool.core.audio

import android.content.Context
import com.jonathan.multitool.core.media.MediaSave
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object WavIo {

    private fun writeHeader(out: OutputStream, pcmBytes: Int, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + pcmBytes); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, channels)
        putInt(24, sampleRate); putInt(28, byteRate); putShort(32, blockAlign); putShort(34, 16)
        putStr(36, "data"); putInt(40, pcmBytes)
        out.write(h)
    }

    /** Saves 16-bit PCM as a WAV file. Returns the display location. */
    fun savePcm(context: Context, pcm: ShortArray, sampleRate: Int, channels: Int, name: String): String {
        val target = MediaSave.openAudio(context, name)
        BufferedOutputStream(target.stream, 1 shl 16).use { out ->
            writeHeader(out, pcm.size * 2, sampleRate, channels)
            val bytes = ByteArray(4096)
            var i = 0
            while (i < pcm.size) {
                var bi = 0
                while (bi < bytes.size && i < pcm.size) {
                    val s = pcm[i].toInt()
                    bytes[bi] = (s and 0xFF).toByte()
                    bytes[bi + 1] = ((s shr 8) and 0xFF).toByte()
                    bi += 2
                    i++
                }
                out.write(bytes, 0, bi)
            }
        }
        target.finish()
        return target.location
    }

    /** Wraps a temp file of raw 16-bit PCM into a WAV. Returns the display location. */
    fun saveRawFile(context: Context, raw: File, sampleRate: Int, channels: Int, name: String): String {
        val target = MediaSave.openAudio(context, name)
        BufferedOutputStream(target.stream, 1 shl 16).use { out ->
            writeHeader(out, raw.length().toInt(), sampleRate, channels)
            FileInputStream(raw).use { it.copyTo(out, 1 shl 16) }
        }
        target.finish()
        return target.location
    }
}
