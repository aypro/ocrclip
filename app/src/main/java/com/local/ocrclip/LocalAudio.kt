package com.local.ocrclip

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Recordings of words for Anki notes, from the `android.db` that the "Local Audio Server for Yomichan" Anki add-on
 * generates (the file AnkiconnectAndroid uses): table `entries` lists who says which word, table `android` holds
 * the audio files. Opened per call, since it's only read when a note is added and the settings may replace it.
 */
object LocalAudio {
    /** An audio file's bytes and its extension ("opus" or "mp3"). */
    class Clip(val bytes: ByteArray, val extension: String)

    /** Outside backups: the file is several GB. */
    fun file(context: Context) = File(context.noBackupFilesDir, "audio.db")

    fun loaded(context: Context) = file(context).exists()

    /** Throws, with a message for the user, unless [file] is a local audio database. */
    fun validate(file: File) = open(file).close()

    /**
     * A recording of [term] read as [reading], from the first source in [SOURCES] that has one. Rows without a
     * reading (Forvo's) match any, so they come after every source that names it: 開く there may be あく or ひらく.
     */
    fun find(context: Context, term: String, reading: String): Clip? = open(file(context)).use { db ->
        val wanted = AnkiNote.hiragana(reading)
        class Row(val named: Boolean, val source: String, val file: String)
        val rows = db.rawQuery("SELECT reading, source, file FROM entries WHERE expression = ?", arrayOf(term)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val r = if (c.isNull(0)) null else AnkiNote.hiragana(c.getString(0))
                    if (r == null || wanted.isEmpty() || r == wanted) add(Row(r != null, c.getString(1), c.getString(2)))
                }
            }
        }
        rows.sortedWith(compareBy({ !it.named }, { SOURCES.indexOf(it.source).let { i -> if (i < 0) SOURCES.size else i } }))
            .firstNotNullOfOrNull { row ->
                val extension = row.file.substringAfterLast('.', "").lowercase()
                if (extension !in TYPES) return@firstNotNullOfOrNull null
                db.rawQuery("SELECT data FROM android WHERE file = ? AND source = ?", arrayOf(row.file, row.source)).use { c ->
                    if (c.moveToFirst()) Clip(c.getBlob(0), extension) else null
                }
            }
    }

    /** How many words have a recording. Reads the whole index, so not for the main thread. */
    fun words(context: Context): Int = open(file(context)).use { db ->
        db.rawQuery("SELECT count(*) FROM (SELECT DISTINCT expression FROM entries)", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    private fun open(file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        if ("entries" !in tables || "android" !in tables) {
            db.close()
            throw IllegalStateException(
                "it isn't an android.db from the Local Audio Server add-on (Generate Android database)",
            )
        }
        return db
    }

    /** MIME types by extension, as AnkiDroid needs one to name the stored file. */
    val TYPES = mapOf("opus" to "audio/ogg", "ogg" to "audio/ogg", "mp3" to "audio/mpeg", "aac" to "audio/aac")

    /** The add-on's default order: dictionary recordings with the standard accent first. */
    private val SOURCES = listOf("nhk16", "shinmeikai8", "forvo", "jpod", "jpod_alternate")
}
