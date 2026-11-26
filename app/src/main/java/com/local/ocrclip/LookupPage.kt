package com.local.ocrclip

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * The offline dictionary overlay: the captured text, tappable per character, above the entries found from the
 * character picked. D-pad right jumps past the word just looked up, left steps back a character. Cross-reference
 * links in the entries, and any other Japanese text tapped in them, look their word up in place; B returns.
 */
object LookupPage {
    /**
     * [fontVersion] is set when a font is loaded for Japanese text (served by the overlay at /font); it changes with
     * the file so the WebView doesn't reuse an old one. lang="ja" picks Japanese glyphs (直, 骨…) from CJK fonts that
     * would otherwise follow the system locale and draw the Chinese forms.
     */
    fun page(context: Context, text: String, fontVersion: String? = null, panel: Boolean = false): String {
        val (css, script) = shared(context)
        return """<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>$css${fontVersion?.let { fontCss(it) } ?: ""}${if (panel) PANEL_CSS else ""}</style></head>
<body><div id="text"></div><div id="results"></div>${if (panel) "<button id=\"back\" aria-label=\"Back\">‹</button>" else ""}
<script>
const text = ${JSONObject.quote(text)};
$script
</script></body></html>"""
    }

    /**
     * The page's CSS and script, from shared/ (packed as assets). The desktop app in desktop/ loads the same files, so
     * both show the same page. Read once.
     */
    @Volatile private var shared: Pair<String, String>? = null

    private fun shared(context: Context): Pair<String, String> = shared ?: Pair(
        context.assets.open("lookup.css").bufferedReader().use { it.readText() },
        context.assets.open("lookup.js").bufferedReader().use { it.readText() },
    ).also { shared = it }

    // Only kana, kanji and full-width forms, so English glosses keep the system's Latin font.
    private fun fontCss(version: String) = """
@font-face { font-family: OCRClipJa; src: url('/font?v=$version');
    unicode-range: U+3000-30FF, U+3400-4DBF, U+4E00-9FFF, U+F900-FAFF, U+FF00-FFEF, U+20000-2FFFF; }
body { font-family: OCRClipJa, sans-serif; }
"""

    fun message(text: String) = "<div class=\"message\">${esc(text)}</div>"

    /**
     * [anki] adds a + button to each definition, which the page script sends to `OCRClip.addToAnki`, and one to each
     * sense of a definition that has several (see [senseCount]). Each dictionary's [styles] (its styles.css) applies
     * to its own entries, as in Yomitan.
     */
    fun results(result: LocalDictionary.Result, anki: Boolean = false, styles: Map<String, String> = emptyMap()): String {
        result.message?.let { return message(it) }
        if (result.entries.isEmpty() && result.kanji.isEmpty()) return message("No entries")
        return buildString {
            for ((ei, e) in result.entries.withIndex()) {
                append("<div class=\"entry\"><div class=\"head\">")
                if (e.reading.isNotEmpty() && e.reading != e.term) {
                    append("<ruby class=\"term\">${esc(e.term)}<rt>${esc(e.reading)}</rt></ruby>")
                } else {
                    append("<span class=\"term\">${esc(e.term)}</span>")
                }
                for (p in e.pitches) for (position in p.positions) append(pitch(e.reading, position, p.dictionary))
                for (f in e.frequencies) append("<span class=\"freq\"><b>${esc(f.dictionary)}</b>${esc(f.value)}</span>")
                append("</div>")
                if (e.reasons.isNotEmpty()) {
                    append("<div class=\"reasons\">« ${e.reasons.joinToString(" « ") { esc(it) }}</div>")
                }
                var dictionary: String? = null
                for ((di, d) in e.definitions.withIndex()) {
                    if (d.dictionary != dictionary) {
                        if (dictionary != null) append("</ol>")
                        append("<div class=\"dict\">${esc(d.dictionary)}</div><ol data-dictionary=\"${esc(d.dictionary)}\">")
                        dictionary = d.dictionary
                    }
                    append("<li>")
                    if (anki) append("<span class=\"add\" data-e=\"$ei\" data-d=\"$di\">+</span>")
                    for (t in d.tags) append("<span class=\"tag\" title=\"${esc(t.notes)}\">${esc(t.name)}</span>")
                    val senses = if (anki && senseCount(d.glossary) >= 2) Senses(ei, di) else null
                    append(glossary(d.glossary, senses))
                    append("</li>")
                }
                if (dictionary != null) append("</ol>")
                append("</div>")
            }
            for (k in result.kanji) {
                append("<div class=\"entry\"><div class=\"head\"><span class=\"kanji\">${esc(k.character)}</span></div>")
                append("<div class=\"dict\">${esc(k.dictionary)}</div>")
                if (k.onyomi.isNotBlank()) append("<div class=\"readings\">${esc(k.onyomi)}</div>")
                if (k.kunyomi.isNotBlank()) append("<div class=\"readings\">${esc(k.kunyomi)}</div>")
                append("<ol>${k.meanings.joinToString("") { "<li>${esc(it)}</li>" }}</ol></div>")
            }
            val css = result.entries.flatMap { e -> e.definitions.map { it.dictionary } }.distinct()
                .mapNotNull { title -> styles[title]?.let { scopeCss(it, title, ".entry") } }
            if (css.isNotEmpty()) append("<style>${css.joinToString("")}</style>")
        }
    }

    private fun glossary(items: JSONArray, senses: Senses?): String {
        val parts = (0 until items.length()).map { item(items.opt(it), false, senses) }.filter { it.isNotEmpty() }
        return if (parts.size == 1) parts[0] else "<ul class=\"gloss\">${parts.joinToString("") { "<li>$it</li>" }}</ul>"
    }

    /**
     * [definitions] as Yomitan writes its {glossary} into Anki fields: the same markup, class names (gloss-sc-*) and
     * data-sc-* attributes, so note types made for Yomitan (Lapis) style them the same way. Each dictionary's
     * styles.css follows in a <style>, scoped to that dictionary's entries. [single] writes the first definition alone,
     * like Yomitan's brief glossary: no numbered list, no dictionary name or tags, and no bullet for a lone item.
     */
    fun ankiGlossary(
        definitions: List<LocalDictionary.Definition>, styles: Map<String, String>, single: Boolean = false,
    ): String = buildString {
        append("<div class=\"yomitan-glossary\">")
        if (single) {
            definitions.firstOrNull()?.let { append(ankiDefinition(it, styles, single = true)) }
        } else {
            append("<ol data-count=\"${definitions.size}\">")
            for (d in definitions) append(ankiDefinition(d, styles, single = false))
            append("</ol>")
        }
        append("</div>")
        val css = definitions.map { it.dictionary }.distinct().mapNotNull { title -> styles[title]?.let { scopeCss(it, title) } }
        if (css.isNotEmpty()) append("<style>${css.joinToString("")}</style>")
    }

    private fun ankiDefinition(
        d: LocalDictionary.Definition, styles: Map<String, String>, single: Boolean,
    ) = buildString {
        val tag = if (single) "div" else "li"
        val styled = d.dictionary in styles
        append("<$tag data-dictionary=\"${esc(d.dictionary)}\"${if (styled) " data-has-styles=\"\"" else ""}>")
        // Like Yomitan's brief glossary, a lone definition goes without its tags (★, priority form) and its name.
        if (!single) {
            for (t in d.tags) {
                append("<span class=\"gloss-tag\" data-category=\"${esc(t.category)}\" title=\"${esc(t.notes)}\">")
                append(esc(t.name).replace(" ", "&nbsp;")).append("</span>")
            }
            append("<i>(${esc(d.dictionary)})</i>")
        }
        val items = (0 until d.glossary.length()).map { item(d.glossary.opt(it), true) }.filter { it.isNotEmpty() }
        if (single && items.size == 1) {
            append(items[0])
        } else {
            append("<ul class=\"gloss-list\" data-count=\"${items.size}\">")
            for (it in items) append("<li class=\"gloss-item\"><div class=\"gloss-content\">$it</div></li>")
            append("</ul>")
        }
        append("</$tag>")
    }

    /**
     * Prefixes every selector of a dictionary's styles.css with `[root] [data-dictionary="title"]`, as Yomitan does
     * for Anki, so its rules reach only its own entries. Rules inside @media and @supports are scoped too; other
     * at-rules are copied as they are.
     */
    fun scopeCss(css: String, title: String, root: String = ".yomitan-glossary"): String {
        val prefix = "$root [data-dictionary=\"${title.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"
        // No "<" can end the <style> element early; CSS doesn't need it outside strings.
        return scope(css.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace("<", ""), prefix)
    }

    private fun scope(css: String, prefix: String): String = buildString {
        var i = 0
        while (true) {
            val open = css.indexOf('{', i)
            if (open < 0) break
            var depth = 0
            var close = open
            while (close < css.length) {
                if (css[close] == '{') depth++
                if (css[close] == '}' && --depth == 0) break
                close++
            }
            // Anything before the last ';' is a statement such as @charset or @import, which doesn't belong in a field.
            val prelude = css.substring(i, open).substringAfterLast(';').trim()
            val block = css.substring(open + 1, close.coerceAtMost(css.length))
            when {
                prelude.startsWith("@media") || prelude.startsWith("@supports") ->
                    append(prelude).append(" {").append(scope(block, prefix)).append("}")
                prelude.startsWith("@") -> append(prelude).append(" {").append(block).append("}")
                prelude.isNotEmpty() ->
                    append(selectors(prelude).joinToString(", ") { "$prefix $it" }).append(" {").append(block.trim()).append("}\n")
            }
            i = close + 1
        }
    }

    /** A selector list split at its top-level commas, so :is(a, b) stays whole. */
    private fun selectors(list: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, ch) in list.withIndex()) {
            when (ch) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    out += list.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        out += list.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    /** Gives out the + buttons of one definition's senses, numbered in the order [senseCount] and [onlySense] use. */
    private class Senses(val entry: Int, val definition: Int) {
        private var next = 0

        fun button() =
            "<span class=\"add sense\" data-e=\"$entry\" data-d=\"$definition\" data-s=\"${next++}\">+</span>"
    }

    /**
     * How many senses a definition has, counted in document order: elements marked data-content="sense" (Jitendex)
     * and numbered lines in plain text ([senseLines], 新和英).
     */
    fun senseCount(glossary: JSONArray): Int = (0 until glossary.length()).sumOf { i ->
        val g = glossary.opt(i)
        val text = plainText(g)
        when {
            text != null -> senseLines(text)?.size ?: 0
            g is JSONObject && g.optString("type") == "structured-content" -> structuredSenses(g.opt("content"))
            else -> 0
        }
    }

    /**
     * A copy of [glossary] with only sense [index] of [senseCount]'s: other senses go, and so does whatever held only
     * senses that went (Jitendex's sense groups). Parts that never held a sense (part of speech, forms, the headword
     * line) stay. Null if there's no such sense.
     */
    fun onlySense(glossary: JSONArray, index: Int): JSONArray? {
        val copy = JSONArray(glossary.toString())
        val out = JSONArray()
        var next = 0
        var found = false
        fun keep(node: Any?): Boolean {
            if (node is JSONArray) {
                var i = 0
                while (i < node.length()) if (keep(node.opt(i))) i++ else node.remove(i)
                return true
            }
            if (node !is JSONObject) return true
            if (isSense(node)) return (next++ == index).also { if (it) found = true }
            if (node.optString("tag") == "a" || structuredSenses(node.opt("content")) == 0) return true
            return keep(node.opt("content")) && structuredSenses(node.opt("content")) > 0
        }
        for (i in 0 until copy.length()) {
            val g = copy.opt(i)
            val text = plainText(g)
            val lines = text?.let { senseLines(it) }
            when {
                text != null && lines != null -> {
                    val k = index - next
                    next += lines.size
                    if (k !in lines.indices) continue
                    found = true
                    val all = text.split('\n')
                    val kept = (all.subList(0, lines[0].first) + all.slice(lines[k])).joinToString("\n")
                    out.put(if (g is JSONObject) g.put("text", kept) else kept)
                }
                g is JSONObject && g.optString("type") == "structured-content" &&
                    structuredSenses(g.opt("content")) > 0 ->
                    if (keep(g.opt("content")) && structuredSenses(g.opt("content")) > 0) out.put(g)
                else -> out.put(g)
            }
        }
        return if (found) out else null
    }

    private fun plainText(g: Any?): String? = when {
        g is String -> g
        g is JSONObject && g.optString("type") == "text" -> g.optString("text")
        else -> null
    }

    // A sense inside a sense isn't counted, and links don't get buttons, the same way element() renders them.
    private fun structuredSenses(node: Any?): Int = when (node) {
        is JSONArray -> (0 until node.length()).sumOf { structuredSenses(node.opt(it)) }
        is JSONObject -> when {
            isSense(node) -> 1
            node.optString("tag") == "a" -> 0
            else -> structuredSenses(node.opt("content"))
        }
        else -> 0
    }

    private fun isSense(o: JSONObject) =
        o.optString("tag") in TAGS && o.optJSONObject("data")?.optString("content") == "sense"

    /**
     * 新和英's senses, as ranges of line indices: after the headword line, lines starting "1 ", "2 "… while the numbers
     * run on (a phrase after the senses can start its own "1 … 2 …"). The lines up to the next sense belong to a sense
     * (its 〜する form); after the last one only the 〜 lines do, and the phrases that follow are the whole entry's.
     * Null unless there are at least two.
     */
    fun senseLines(text: String): List<IntRange>? {
        val lines = text.split('\n')
        val starts = mutableListOf<Int>()
        for (i in 1 until lines.size) {
            val n = NUMBERED.find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n != starts.size + 1) break
            starts += i
        }
        if (starts.size < 2) return null
        var end = starts.last() + 1
        while (end < lines.size && lines[end].startsWith("〜")) end++
        return starts.mapIndexed { k, start -> start until (starts.getOrNull(k + 1) ?: end) }
    }

    /** Plain text on the page, with a + before each sense when [senses] is given; on a card, newlines become <br>. */
    private fun plain(text: String, anki: Boolean, senses: Senses?): String {
        if (anki) return esc(text.trimEnd('\n')).replace("\n", "<br>")
        val ranges = senses?.let { senseLines(text) } ?: return esc(text)
        val lines = text.split('\n')
        return buildString {
            append(esc(lines.subList(0, ranges[0].first).joinToString("\n")))
            for (r in ranges) {
                append("<div class=\"sense-add\">").append(senses.button())
                append(esc(lines.slice(r).joinToString("\n"))).append("</div>")
            }
            append(esc(lines.subList(ranges.last().last + 1, lines.size).joinToString("\n")))
        }
    }

    private fun item(item: Any?, anki: Boolean, senses: Senses? = null): String = when (item) {
        is String -> plain(item, anki, senses)
        is JSONObject -> when (item.optString("type")) {
            "text" -> plain(item.optString("text"), anki, senses)
            "structured-content" -> content(item.opt("content"), anki, senses)
            "image" -> image(item)
            else -> ""
        }
        else -> ""
    }

    // build_dict.py doesn't keep dictionary images, so their description stands in for them.
    private fun image(o: JSONObject): String {
        val alt = o.optString("alt").ifEmpty { o.optString("description") }
        return if (alt.isEmpty()) "" else "<span class=\"img\">${esc(alt)}</span>"
    }

    /**
     * Yomitan structured content, limited to its own tag set so a dictionary can't inject scripts or real links.
     * Elements get Yomitan's gloss-sc-* class and data-sc-* attributes, which dictionary styles select on; [anki]
     * only changes how links are written. With [senses], each sense element starts with its + button.
     */
    private fun content(node: Any?, anki: Boolean, senses: Senses? = null): String = when (node) {
        is String -> esc(node)
        is JSONArray -> (0 until node.length()).joinToString("") { content(node.opt(it), anki, senses) }
        is JSONObject -> element(node, anki, senses)
        else -> ""
    }

    private fun element(o: JSONObject, anki: Boolean, senses: Senses?): String {
        val tag = o.optString("tag")
        when (tag) {
            "br" -> return "<br>"
            "img" -> return image(o)
            "a" -> return link(o, anki)
            !in TAGS -> return content(o.opt("content"), anki, senses)
        }
        val sense = senses != null && isSense(o)
        val attrs = StringBuilder(" class=\"gloss-sc-$tag${if (sense) " sense-add" else ""}\"")
        o.optJSONObject("data")?.let { data ->
            for (key in data.keys()) {
                if (!key.all { it.isLetterOrDigit() || it == '-' }) continue
                // Yomitan sets element.dataset["sc" + Key], so "Contents" and "contents" both become data-sc-contents.
                val name = kebab("sc" + key.replaceFirstChar { it.uppercaseChar() })
                attrs.append(" data-$name=\"${esc(data.opt(key).toString())}\"")
            }
        }
        o.optJSONObject("style")?.let { style(it) }?.takeIf { it.isNotEmpty() }?.let { attrs.append(" style=\"${esc(it)}\"") }
        o.optString("title").takeIf { it.isNotEmpty() }?.let { attrs.append(" title=\"${esc(it)}\"") }
        o.optString("lang").takeIf { it.isNotEmpty() }?.let { attrs.append(" lang=\"${esc(it)}\"") }
        if (o.has("colSpan")) attrs.append(" colspan=\"${o.optInt("colSpan", 1)}\"")
        if (o.has("rowSpan")) attrs.append(" rowspan=\"${o.optInt("rowSpan", 1)}\"")
        if (tag == "details" && o.optBoolean("open")) attrs.append(" open")
        val button = if (sense) senses!!.button() else ""
        return "<$tag$attrs>$button${content(o.opt("content"), anki, if (sense) null else senses)}</$tag>"
    }

    private fun kebab(name: String) = name.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }

    /**
     * Internal links ("?query=見る&wildcards=off", maybe with "&primary_reading=") point at Yomitan's search page; the
     * page script looks them up in place. Anything else (web pages, dictionary appendices) stays inert, since
     * following it would only navigate the overlay away.
     */
    private fun link(o: JSONObject, anki: Boolean): String {
        val inner = content(o.opt("content"), anki)
        val href = o.optString("href")
        if (anki) {
            // On a card a web link can open; an internal one would lead nowhere, so it keeps only the look of a link.
            val web = href.startsWith("https://") || href.startsWith("http://")
            return "<a class=\"gloss-sc-a\"${if (web) " href=\"${esc(href)}\"" else ""}>$inner</a>"
        }
        if (!href.startsWith("?")) return "<span class=\"link\">$inner</span>"
        val params = href.substring(1).split('&').associate {
            it.substringBefore('=') to Uri.decode(it.substringAfter('=', ""))
        }
        val query = params["query"].orEmpty()
        if (query.isEmpty()) return "<span class=\"link\">$inner</span>"
        val reading = params["primary_reading"].orEmpty()
        return "<span class=\"link\" data-q=\"${esc(query)}\" data-r=\"${esc(reading)}\">$inner</span>"
    }

    private fun style(style: JSONObject): String = style.keys().asSequence().mapNotNull { key ->
        if (!key.all { it.isLetter() }) return@mapNotNull null
        val value = when (val v = style.opt(key)) {
            is Number -> "${v}em" // Yomitan's unit for numeric margins and padding
            is String -> v
            is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
            else -> return@mapNotNull null
        }
        if (!SAFE_CSS.matches(value) || "url" in value.lowercase()) return@mapNotNull null
        "${kebab(key)}:$value"
    }.joinToString(";")

    /** Reading with the pitch accent drawn over it: a line over high morae and a tick at the drop. */
    private fun pitch(reading: String, position: String, dictionary: String): String {
        val title = esc(dictionary)
        val drop = position.toIntOrNull() ?: return "<span class=\"pitch\" title=\"$title\">${esc(position)}</span>"
        val morae = mutableListOf<String>()
        for (ch in reading) if (ch in SMALL_KANA && morae.isNotEmpty()) morae[morae.lastIndex] += ch else morae += "$ch"
        val html = morae.mapIndexed { i, mora ->
            val high = when (drop) {
                0 -> i > 0
                1 -> i == 0
                else -> i in 1 until drop
            }
            val classes = listOfNotNull(if (high) "h" else null, if (i == drop - 1) "d" else null).joinToString(" ")
            "<span class=\"$classes\">${esc(mora)}</span>"
        }.joinToString("")
        return "<span class=\"pitch\" title=\"$title\">$html<span class=\"n\">[$drop]</span></span>"
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    private val TAGS = setOf(
        "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "span", "div", "ol", "ul", "li",
        "details", "summary",
    )
    private val NUMBERED = Regex("""^(\d+) """)
    private val SAFE_CSS = Regex("""[\w\s#%.,()+-]*""")
    private const val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

    /**
     * On the second screen no key reaches the page, so a ‹ chip steps back after following links. It sits left of the
     * window's ✕ (36 dp, 6 dp from the corner), and the captured text keeps clear of both.
     */
    private const val PANEL_CSS = """
#text { padding-right: 96px; }
#back { position: fixed; top: 6px; right: 48px; width: 36px; height: 36px; padding: 0; border: 0; border-radius: 50%;
    background: rgba(34, 34, 34, 0.8); color: #aaa; font-size: 26px; line-height: 34px; display: none; z-index: 2; }
#back.on { display: block; }
"""
}
