package com.local.ocrclip

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import java.io.File

/**
 * AnkiDroid's content provider API, used directly rather than through its library (the contract is FlashCardsContract
 * and AddContentApi in AnkiDroid's api module). Everything needs AnkiDroid's READ_WRITE_DATABASE permission, a
 * runtime one the settings screen asks for.
 */
class Anki(private val context: Context) {
    class Model(val id: Long, val name: String, val fields: List<String>)

    class Deck(val id: Long, val name: String)

    val installed get() = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    val permitted get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun models(): List<Model> = query(Uri.parse("$BASE/models")) { model(it) }.sortedBy { it.name.lowercase() }

    fun model(id: Long): Model? = query(Uri.parse("$BASE/models/$id")) { model(it) }.firstOrNull()

    fun decks(): List<Deck> = query(Uri.parse("$BASE/decks")) { c ->
        Deck(c.getLong(c.getColumnIndexOrThrow("deck_id")), c.getString(c.getColumnIndexOrThrow("deck_name")))
    }.sortedBy { it.name.lowercase() }

    /**
     * Copies [uri] into AnkiDroid's media folder under a name starting with [name] and returns the stored file's name.
     * AnkiDroid reads the file through a one-off grant, and takes the extension from the provider's type.
     */
    fun addMedia(uri: Uri, name: String): String {
        context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put("file_uri", uri.toString())
                put("preferred_name", name.replace(' ', '_'))
            }
            val stored = context.contentResolver.insert(Uri.parse("$BASE/media"), values)
                ?: error("AnkiDroid couldn't store the screenshot")
            return File(stored.path!!).name
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun addNote(modelId: Long, deckId: Long, fields: List<String>, tags: List<String>) {
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields.joinToString(FIELD_SEPARATOR))
            if (tags.isNotEmpty()) put("tags", tags.joinToString(" "))
        }
        // A single insert ignores the deck and uses the note type's; only bulkInsert reads it (AddContentApi.addNotes).
        val uri = Uri.parse("$BASE/notes").buildUpon().appendQueryParameter("deckId", deckId.toString()).build()
        if (context.contentResolver.bulkInsert(uri, arrayOf(values)) < 1) {
            error("AnkiDroid didn't add the note (its first field may be empty)")
        }
    }

    private fun model(c: Cursor) = Model(
        c.getLong(c.getColumnIndexOrThrow("_id")),
        c.getString(c.getColumnIndexOrThrow("name")),
        c.getString(c.getColumnIndexOrThrow("field_names")).split(FIELD_SEPARATOR),
    )

    private fun <T> query(uri: Uri, each: (Cursor) -> T): List<T> =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(each(c)) }
        } ?: error("AnkiDroid isn't answering; open it once")

    companion object {
        const val PACKAGE = "com.ichi2.anki"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val BASE = "content://$AUTHORITY"
        private const val FIELD_SEPARATOR = "\u001f"

        /** Anki's default deck, which always exists. */
        const val DEFAULT_DECK = 1L
    }
}
