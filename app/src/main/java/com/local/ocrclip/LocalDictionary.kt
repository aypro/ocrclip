package com.local.ocrclip

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.Normalizer
import java.util.zip.Inflater

/**
 * Offline lookup in Yomitan dictionaries, from the database tools/build_dict.py builds on a PC (parsing the zips on a
 * handheld would take far too long). The lookup page calls [lookup] through the WebView's JavaScript bridge.
 */
class LocalDictionary(private val context: Context) {
    class Info(
        val id: Int, val title: String, val terms: Int, val meta: Int, val kanji: Int, val enabled: Boolean,
    )

    class Definition(val dictionary: String, val tags: List<Pair<String, String>>, val glossary: JSONArray)

    class Frequency(val dictionary: String, val value: String)

    class Pitch(val dictionary: String, val positions: List<String>)

    class Entry(
        val term: String, val reading: String, val reasons: List<String>, val definitions: List<Definition>,
        val frequencies: List<Frequency>, val pitches: List<Pitch>,
    )

    class Kanji(
        val dictionary: String, val character: String, val onyomi: String, val kunyomi: String,
        val meanings: List<String>,
    )

    /** [length] is how much of the text from the offset the longest entry covers, for highlighting. */
    class Result(val length: Int, val entries: List<Entry>, val kanji: List<Kanji>, val message: String? = null)

    private class Match(
        val id: Long, val dictionary: Info, val rank: Int, val term: String, val reading: String, val tags: List<String>,
        val score: Int, val length: Int, val reasons: List<String>,
    )

    private val prefs = Prefs(context)
    private val deinflector by lazy {
        Deinflector(context.assets.open("japanese-transforms.json").bufferedReader().use { it.readText() })
    }
    private var db: SQLiteDatabase? = null
    private var tagNotes = emptyMap<String, String>()

    /** Each dictionary's deflate preset, which its compressed glossaries were built against. */
    private var presets = emptyMap<Int, ByteArray?>()

    /** lastModified of the open file, so a database loaded in the settings replaces the open one. */
    private var opened = 0L

    /** Why the database couldn't be opened, for the settings screen. */
    var error: String? = null
        private set

    @Synchronized
    private fun database(): SQLiteDatabase? {
        val f = file(context)
        if (db != null && f.lastModified() == opened) return db
        close()
        if (!f.exists()) return null
        opened = f.lastModified()
        db = runCatching { open(f) }.onFailure { error = it.message }.getOrNull()?.also { d ->
            val notes = HashMap<String, String>()
            d.rawQuery("SELECT dict_id, name, notes FROM tags", null).use { c ->
                while (c.moveToNext()) notes["${c.getInt(0)}:${c.getString(1)}"] = c.getString(2)
            }
            tagNotes = notes
            presets = d.rawQuery("SELECT id, preset FROM dictionaries", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getInt(0), if (c.isNull(1)) null else c.getBlob(1)) }
            }
        }
        return db
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
        error = null
    }

    /** Dictionaries in display order, or null without a database. */
    fun dictionaries(): List<Info>? = database()?.let { dictionaries(it) }

    private fun dictionaries(db: SQLiteDatabase): List<Info> {
        val order = prefs.dictionaryOrder
        val disabled = prefs.disabledDictionaries
        val all = mutableListOf<Pair<Int, Info>>()
        db.rawQuery("SELECT id, title, priority, terms, meta, kanji FROM dictionaries", null).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)
                all += c.getInt(2) to Info(c.getInt(0), title, c.getInt(3), c.getInt(4), c.getInt(5), title !in disabled)
            }
        }
        // Titles the user ordered come first, the rest keep the order they were given to build_dict.py.
        return all.sortedWith(compareBy({ order.indexOf(it.second.title).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { it.first })).map { it.second }
    }

    @JavascriptInterface
    fun lookup(text: String, offset: Int): String {
        val json = JSONObject()
        try {
            val result = find(text, offset)
            json.put("length", result?.length ?: 0)
            json.put("html", if (result == null) LookupPage.message(
                error?.let { "The dictionary database couldn't be opened: $it" }
                    ?: "No dictionary database loaded. Build one on a PC with tools/build_dict.py, then load it in " +
                    "OCR Clip's settings."
            ) else LookupPage.results(result))
        } catch (e: Exception) {
            json.put("length", 0).put("html", LookupPage.message("Lookup failed: ${e.message}"))
        }
        return json.toString()
    }

    /** Entries for words starting at [offset] (a UTF-16 index), longest match first, like Yomitan's scan. */
    fun find(text: String, offset: Int): Result? {
        val db = database() ?: return null
        val dictionaries = dictionaries(db).filter { it.enabled }
        if (dictionaries.isEmpty()) {
            return Result(0, emptyList(), emptyList(), "All dictionaries are turned off in OCR Clip's settings.")
        }
        val byId = dictionaries.associateBy { it.id }
        val rank = dictionaries.withIndex().associate { (i, d) -> d.id to i }
        val seen = HashSet<Long>()
        val matches = mutableListOf<Match>()
        for (end in minOf(text.length, offset + MAX_LENGTH) downTo offset + 1) {
            val candidates = variants(text.substring(offset, end))
                .flatMap { deinflector.deinflect(it) }
                .filter { it.text.isNotEmpty() }
                .distinctBy { it.text to it.conditions } // breadth-first, so this keeps the shortest chain
                .groupBy { it.text }
            // Glossaries are left out here: short kana match hundreds of entries, and only the shown ones are read.
            query(
                db, "SELECT rowid, dict_id, term, reading, def_tags, rules, score, term_tags FROM terms " +
                    "WHERE term IN (%s) OR reading IN (%s)", candidates.keys.toList(),
            ) { c ->
                val dictionary = byId[c.getInt(1)] ?: return@query
                val term = c.getString(2)
                val reading = c.getString(3)
                val pos = deinflector.conditionsOf(c.getString(5))
                val candidate = (candidates[term].orEmpty() + candidates[reading].orEmpty())
                    .firstOrNull { Deinflector.matches(it.conditions, pos) } ?: return@query
                if (!seen.add(c.getLong(0))) return@query
                val tags = (c.getString(4).split(' ') + c.getString(7).split(' ')).filter { it.isNotEmpty() }.distinct()
                matches += Match(
                    c.getLong(0), dictionary, rank.getValue(dictionary.id), term, reading, tags, c.getInt(6),
                    end - offset, candidate.reasons,
                )
            }
        }

        // One entry per term and reading, ordered like Yomitan: longest match, fewest inflections undone, dictionary
        // order, then the dictionary's own score.
        val groups = matches.groupBy { it.term to it.reading }.values
            .map { group -> group.sortedWith(compareBy({ it.rank }, { -it.score })) }
            .sortedWith(compareBy(
                { g -> -g.maxOf { it.length } }, { g -> g.minOf { it.reasons.size } },
                { g -> g.minOf { it.rank } }, { g -> -g.maxOf { it.score } },
            ))
            .take(MAX_ENTRIES)
        val metaByTerm = meta(db, groups.map { it.first().term }.distinct(), byId, rank)
        val glossaries = glossaries(db, groups.flatten().map { it.id })
        val entries = groups.map { group ->
            val best = group.minWith(compareBy({ -it.length }, { it.reasons.size }))
            val forms = metaByTerm[best.term].orEmpty().filter { it.reading == null || it.reading == best.reading }
            Entry(
                best.term, best.reading, best.reasons,
                group.map { m ->
                    Definition(m.dictionary.title, m.tags.map { it to (tagNotes["${m.dictionary.id}:$it"] ?: "") },
                        JSONArray(glossaries.getValue(m.id)))
                },
                forms.mapNotNull { it.frequency?.let { f -> Frequency(it.dictionary, f) } },
                forms.mapNotNull { it.pitches?.let { p -> Pitch(it.dictionary, p) } },
            )
        }
        return Result(matches.maxOfOrNull { it.length } ?: 0, entries, kanji(db, text, offset, byId, rank))
    }

    /** Decompressed glossary JSON by rowid. */
    private fun glossaries(db: SQLiteDatabase, ids: List<Long>): Map<Long, String> {
        val found = HashMap<Long, String>()
        val inflater = Inflater(true)
        try {
            query(db, "SELECT rowid, dict_id, glossary FROM terms WHERE rowid IN (%s)", ids.map { it.toString() }) { c ->
                found[c.getLong(0)] = inflate(inflater, c.getBlob(2), presets[c.getInt(1)])
            }
        } finally {
            inflater.end()
        }
        return found
    }

    private class Meta(
        val dictionary: String, val rank: Int, val reading: String?, val frequency: String?, val pitches: List<String>?,
    )

    private fun meta(
        db: SQLiteDatabase, terms: List<String>, byId: Map<Int, Info>, rank: Map<Int, Int>,
    ): Map<String, List<Meta>> {
        val found = mutableListOf<Pair<String, Meta>>()
        query(db, "SELECT dict_id, term, mode, data FROM term_meta WHERE term IN (%s)", terms) { c ->
            val dictionary = byId[c.getInt(0)] ?: return@query
            val data = JSONTokener(c.getString(3)).nextValue()
            val reading = (data as? JSONObject)?.optString("reading")?.ifEmpty { null }
            val meta = when (c.getString(2)) {
                "freq" -> frequency(if (data is JSONObject && data.has("frequency")) data.get("frequency") else data)
                    ?.let { Meta(dictionary.title, rank.getValue(dictionary.id), reading, it, null) }
                "pitch" -> (data as? JSONObject)?.optJSONArray("pitches")?.let { p ->
                    (0 until p.length()).mapNotNull { p.optJSONObject(it)?.opt("position")?.toString() }
                }?.let { Meta(dictionary.title, rank.getValue(dictionary.id), reading, null, it) }
                else -> null
            } ?: return@query
            found += c.getString(1) to meta
        }
        return found.sortedBy { it.second.rank }.groupBy({ it.first }, { it.second })
    }

    private fun kanji(db: SQLiteDatabase, text: String, offset: Int, byId: Map<Int, Info>, rank: Map<Int, Int>): List<Kanji> {
        if (offset >= text.length) return emptyList()
        val cp = text.codePointAt(offset)
        if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return emptyList()
        val found = mutableListOf<Pair<Int, Kanji>>()
        query(db, "SELECT dict_id, character, onyomi, kunyomi, meanings FROM kanji WHERE character IN (%s)",
            listOf(String(Character.toChars(cp)))) { c ->
            val dictionary = byId[c.getInt(0)] ?: return@query
            val meanings = JSONArray(c.getString(4)).let { a -> (0 until a.length()).map { a.optString(it) } }
            found += rank.getValue(dictionary.id) to
                Kanji(dictionary.title, c.getString(1), c.getString(2), c.getString(3), meanings)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** Runs [sql] with each `%s` replaced by placeholders for [values], in chunks small enough for SQLite's limit. */
    private fun query(db: SQLiteDatabase, sql: String, values: List<String>, each: (Cursor) -> Unit) {
        val uses = sql.split("%s").size - 1
        // Android's SQLite allows 999 bound parameters per statement.
        for (chunk in values.chunked(999 / uses)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = Array(uses) { chunk }.flatMap { it }.toTypedArray()
            db.rawQuery(sql.replace("%s", marks), args).use { c -> while (c.moveToNext()) each(c) }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2 // tools/build_dict.py SCHEMA_VERSION
        private const val MAX_LENGTH = 16
        private const val MAX_ENTRIES = 32

        fun file(context: Context) = File(context.filesDir, "dict.db")

        /** Throws, with a message for the user, unless [file] is a database this version can read. */
        fun validate(file: File) = open(file).close()

        private fun open(file: File): SQLiteDatabase {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            if (db.version != SCHEMA_VERSION) {
                db.close()
                throw IllegalStateException("it was built by a different version of tools/build_dict.py, rebuild it")
            }
            return db
        }

        /** Raw deflate, as written by build_dict.py's compressor. */
        private fun inflate(inflater: Inflater, data: ByteArray, preset: ByteArray?): String {
            inflater.reset()
            preset?.let { inflater.setDictionary(it) }
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 8)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("corrupt glossary")
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Like Yomitan's text preprocessors: width and compatibility forms folded, and both kana scripts tried. */
        private fun variants(raw: String): Set<String> {
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            return linkedSetOf(text, shiftKana(text, 'ァ', 'ヶ', 'ぁ' - 'ァ'), shiftKana(text, 'ぁ', 'ゖ', 'ァ' - 'ぁ'))
        }

        private fun shiftKana(text: String, from: Char, to: Char, by: Int) =
            buildString { for (ch in text) append(if (ch in from..to) ch + by else ch) }

        private fun frequency(value: Any?): String? = when (value) {
            is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is String -> value
            is JSONObject -> value.optString("displayValue").ifEmpty { null } ?: frequency(value.opt("value"))
            else -> null
        }
    }
}
