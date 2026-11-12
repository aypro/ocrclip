package com.local.ocrclip

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Hands the lookup's screenshot to AnkiDroid, which copies it into its media folder. Not exported: AnkiDroid
 * only gets a read grant for this one URI while a note is added. (FileProvider would mean AndroidX.)
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = if (uri.path == "/$SCREENSHOT") "image/jpeg" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/$SCREENSHOT" || mode != "r") throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(Prefs.lookupScreenshot(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        if (uri.path != "/$SCREENSHOT") return null
        val file = Prefs.lookupScreenshot(context!!)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) SCREENSHOT else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        private const val AUTHORITY = "com.local.ocrclip.files"
        private const val SCREENSHOT = "screenshot.jpg"
        val screenshot: Uri = Uri.parse("content://$AUTHORITY/$SCREENSHOT")
    }
}
