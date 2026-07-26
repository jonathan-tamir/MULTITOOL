package com.jonathan.multitool.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** Saves media into public collections (API 29+) or the app's external dir (older). */
object MediaSave {

    class Target(val stream: OutputStream, val location: String, private val done: () -> Unit) {
        fun finish() = done()
    }

    fun openAudio(context: Context, name: String): Target =
        open(context, name, "audio/wav", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MUSIC, "Music/JSA")

    fun openImage(context: Context, name: String): Target =
        open(context, name, "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PICTURES, "Pictures/JSA")

    fun openVideo(context: Context, name: String): Target =
        open(context, name, "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES, "Movies/JSA")

    private fun open(
        context: Context, name: String, mime: String, collection: Uri,
        legacyDir: String, relPath: String
    ): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("MediaStore open failed")
            return Target(stream, "$relPath/$name") {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, v, null, null)
            }
        } else {
            val dir = context.getExternalFilesDir(legacyDir) ?: context.filesDir
            val file = File(dir, name)
            return Target(FileOutputStream(file), file.absolutePath) { }
        }
    }

    /** Copies a finished temp file into a public collection. Returns the display location. */
    fun publishFile(context: Context, temp: File, name: String, kind: String): String {
        val target = when (kind) {
            "video" -> openVideo(context, name)
            "audio" -> openAudio(context, name)
            else -> openImage(context, name)
        }
        target.stream.use { out ->
            FileInputStream(temp).use { it.copyTo(out, 1 shl 16) }
        }
        target.finish()
        return target.location
    }
}
