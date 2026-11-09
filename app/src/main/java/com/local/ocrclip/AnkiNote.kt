package com.local.ocrclip

/**
 * Field values for an Anki note from one looked-up entry, matched by field name the way Yomitan's Lapis setup fills
 * them: Expression, ExpressionFurigana, ExpressionReading, MainDefinition (the definition picked), Glossary (all of
 * the entry's), Sentence, Picture, PitchPosition, Frequency and FreqSort. A few common names from other note types
 * (Word, Reading, Meaning, Front/Back…) map too; other fields stay empty.
 */
object AnkiNote {
    class Source(
        val entry: LocalDictionary.Entry,
        val definition: LocalDictionary.Definition,
        /** The captured text and the word's offset in it, or -1 when the entry came from a followed link. */
        val text: String,
        val offset: Int,
        /** Stored media file name of the screenshot, or null without one. */
        val picture: String?,
        val styles: Map<String, String>,
    )

    private enum class Value { TERM, READING, FURIGANA, DEFINITION, GLOSSARY, SENTENCE, PICTURE, PITCH, FREQUENCY, FREQ_SORT }

    private val names = mapOf(
        Value.TERM to setOf("expression", "word", "term", "vocab", "vocabulary", "front", "key"),
        Value.READING to setOf("expressionreading", "reading", "kana", "wordreading", "vocabkana"),
        Value.FURIGANA to setOf("expressionfurigana", "furigana", "wordfurigana", "vocabfurigana"),
        Value.DEFINITION to setOf("maindefinition", "definition", "meaning", "back", "glossaryfirst"),
        Value.GLOSSARY to setOf("glossary", "definitions"),
        Value.SENTENCE to setOf("sentence"),
        Value.PICTURE to setOf("picture", "image", "screenshot"),
        Value.PITCH to setOf("pitchposition", "pitch", "pitchaccent"),
        Value.FREQUENCY to setOf("frequency", "frequencies"),
        Value.FREQ_SORT to setOf("freqsort", "frequencysort"),
    ).flatMap { (value, names) -> names.map { it to value } }.toMap()

    private fun valueOf(field: String) = names[field.lowercase().filter { it in 'a'..'z' }]

    fun wantsPicture(fields: List<String>) = fields.any { valueOf(it) == Value.PICTURE }

    /** The field [fields] puts the term in, which the duplicate check searches. */
    fun termField(fields: List<String>): String = fields.firstOrNull { valueOf(it) == Value.TERM } ?: fields[0]

    fun fields(fields: List<String>, s: Source): List<String> = fields.mapIndexed { i, name ->
        when (valueOf(name)) {
            Value.TERM -> s.entry.term
            Value.READING -> s.entry.reading
            Value.FURIGANA -> furigana(s.entry.term, s.entry.reading)
            Value.DEFINITION -> LookupPage.ankiGlossary(listOf(s.definition), s.styles)
            Value.GLOSSARY -> LookupPage.ankiGlossary(s.entry.definitions, s.styles)
            Value.SENTENCE -> sentence(s)
            Value.PICTURE -> s.picture?.let { "<img src=\"${esc(it)}\">" } ?: ""
            Value.PITCH -> s.entry.pitches.flatMap { it.positions }.distinct().let {
                if (it.size <= 1) it.joinToString("") else it.joinToString("", "<ol>", "</ol>") { p -> "<li>${esc(p)}</li>" }
            }
            Value.FREQUENCY -> s.entry.frequencies.takeIf { it.isNotEmpty() }
                ?.joinToString("", "<ul>", "</ul>") { "<li>${esc(it.dictionary)}: ${esc(it.value)}</li>" } ?: ""
            Value.FREQ_SORT -> harmonicRank(s.entry).toString()
            // Anki sorts and checks duplicates by the first field, so a note type without a known one gets the word there.
            null -> if (i == 0) s.entry.term else ""
        }
    }

    /** The captured text with the word in bold, as Lapis expects. */
    private fun sentence(s: Source): String {
        val text = s.text
        val start = s.offset
        val end = (start + s.entry.length).coerceAtMost(text.length)
        val html = if (start < 0 || start >= end) esc(text)
        else esc(text.substring(0, start)) + "<b>" + esc(text.substring(start, end)) + "</b>" + esc(text.substring(end))
        return html.replace("\n", "<br>")
    }

    /**
     * Yomitan's frequency-harmonic-rank: the harmonic mean of the first number from each frequency dictionary, or
     * 9999999 without any, so unranked words sort last.
     */
    private fun harmonicRank(entry: LocalDictionary.Entry): Int {
        val ranks = entry.frequencies.groupBy { it.dictionary }.values.mapNotNull { list ->
            list.firstNotNullOfOrNull { f -> Regex("""\d+""").find(f.value)?.value?.toDoubleOrNull()?.takeIf { it > 0 } }
        }
        if (ranks.isEmpty()) return 9999999
        return (ranks.size / ranks.sumOf { 1 / it }).toInt()
    }

    /**
     * Anki furigana with the reading split over the kanji runs, like Yomitan's furigana-plain: 取り消す/とりけす →
     * "取[と]り 消[け]す". When the kana in the word don't line up with the reading, the whole word gets it.
     */
    fun furigana(term: String, reading: String): String {
        if (reading.isEmpty() || hiragana(term) == hiragana(reading)) return term
        val groups = mutableListOf<Pair<Boolean, String>>() // (kana, text)
        for (ch in term) {
            val kana = isKana(ch)
            if (groups.isNotEmpty() && groups.last().first == kana) groups[groups.lastIndex] = kana to groups.last().second + ch
            else groups += kana to "$ch"
        }
        val pattern = groups.joinToString("", "^", "$") { (kana, text) -> if (kana) Regex.escape(hiragana(text)) else "(.+?)" }
        val match = Regex(pattern).matchEntire(hiragana(reading)) ?: return "$term[$reading]"
        var group = 1
        return buildString {
            for ((kana, text) in groups) {
                if (kana) {
                    append(text)
                } else {
                    // Furigana applies back to the last space, so each kanji run after other text needs one before it.
                    if (isNotEmpty()) append(' ')
                    append(text).append('[').append(reading.substring(match.groups[group++]!!.range)).append(']')
                }
            }
        }
    }

    private fun isKana(ch: Char) = ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヺ' || ch == 'ー' || ch == 'ゝ' || ch == 'ゞ'

    private fun hiragana(s: String) = buildString { for (ch in s) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
