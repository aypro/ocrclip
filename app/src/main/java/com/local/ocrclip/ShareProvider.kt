package com.local.ocrclip

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the lookup's screenshot and the word's recording to AnkiDroid, which copies them into its media folder. Not
 * exported: AnkiDroid only gets a read grant for the one URI while a note is added. (FileProvider would mean
 * AndroidX.)
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    /** AnkiDroid takes the stored file's extension from this. */
    override fun getType(uri: Uri): String? = when {
        uri.path == "/$SCREENSHOT" -> "image/jpeg"
        else -> audioExtension(uri)?.let { LocalAudio.TYPES[it] }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = file(uri)
        if (file == null || mode != "r") throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val file = file(uri) ?: return null
        val name = uri.lastPathSegment
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) name else null })
        }
    }

    private fun file(uri: Uri): File? = when {
        uri.path == "/$SCREENSHOT" -> Prefs.lookupScreenshot(context!!)
        else -> audioExtension(uri)?.let { Prefs.lookupAudio(context!!, it) }
    }

    /** The extension of an audio URI, if it's one this provider serves. */
    private fun audioExtension(uri: Uri): String? =
        uri.path?.takeIf { it.startsWith("/$AUDIO.") }?.substringAfterLast('.')?.takeIf { it in LocalAudio.TYPES }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        private const val AUTHORITY = "com.local.ocrclip.files"
        private const val SCREENSHOT = "screenshot.jpg"
        private const val AUDIO = "audio"
        val screenshot: Uri = Uri.parse("content://$AUTHORITY/$SCREENSHOT")

        /** The recording in `Prefs.lookupAudio(context, extension)`. */
        fun audio(extension: String): Uri = Uri.parse("content://$AUTHORITY/$AUDIO.$extension")
    }
}
