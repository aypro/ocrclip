package com.local.ocrclip

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * The offline dictionary overlay: the captured text, tappable per character, above the entries found from the
 * character picked. D-pad right jumps past the word just looked up, left steps back a character. Cross-reference
 * links in the entries look their word up in place; B returns.
 */
object LookupPage {
    fun page(text: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>$CSS</style></head>
<body><div id="text"></div><div id="results"></div>
<script>
const text = ${JSONObject.quote(text)};
$SCRIPT
</script></body></html>"""

    fun message(text: String) = "<div class=\"message\">${esc(text)}</div>"

    fun results(result: LocalDictionary.Result): String {
        result.message?.let { return message(it) }
        if (result.entries.isEmpty() && result.kanji.isEmpty()) return message("No entries")
        return buildString {
            for (e in result.entries) {
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
                for (d in e.definitions) {
                    if (d.dictionary != dictionary) {
                        if (dictionary != null) append("</ol>")
                        append("<div class=\"dict\">${esc(d.dictionary)}</div><ol>")
                        dictionary = d.dictionary
                    }
                    append("<li>")
                    for ((name, notes) in d.tags) append("<span class=\"tag\" title=\"${esc(notes)}\">${esc(name)}</span>")
                    append(glossary(d.glossary))
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
        }
    }

    private fun glossary(items: JSONArray): String {
        val parts = (0 until items.length()).map { item(items.opt(it)) }.filter { it.isNotEmpty() }
        return if (parts.size == 1) parts[0] else "<ul class=\"gloss\">${parts.joinToString("") { "<li>$it</li>" }}</ul>"
    }

    private fun item(item: Any?): String = when (item) {
        is String -> esc(item)
        is JSONObject -> when (item.optString("type")) {
            "text" -> esc(item.optString("text"))
            "structured-content" -> content(item.opt("content"))
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

    /** Yomitan structured content, limited to its own tag set so a dictionary can't inject scripts or real links. */
    private fun content(node: Any?): String = when (node) {
        is String -> esc(node)
        is JSONArray -> (0 until node.length()).joinToString("") { content(node.opt(it)) }
        is JSONObject -> element(node)
        else -> ""
    }

    private fun element(o: JSONObject): String {
        val tag = o.optString("tag")
        when (tag) {
            "br" -> return "<br>"
            "img" -> return image(o)
            "a" -> return link(o)
            !in TAGS -> return content(o.opt("content"))
        }
        val attrs = StringBuilder()
        o.optJSONObject("style")?.let { style(it) }?.takeIf { it.isNotEmpty() }?.let { attrs.append(" style=\"${esc(it)}\"") }
        o.optString("title").takeIf { it.isNotEmpty() }?.let { attrs.append(" title=\"${esc(it)}\"") }
        o.optString("lang").takeIf { it.isNotEmpty() }?.let { attrs.append(" lang=\"${esc(it)}\"") }
        if (o.has("colSpan")) attrs.append(" colspan=\"${o.optInt("colSpan", 1)}\"")
        if (o.has("rowSpan")) attrs.append(" rowspan=\"${o.optInt("rowSpan", 1)}\"")
        if (tag == "details" && o.optBoolean("open")) attrs.append(" open")
        return "<$tag$attrs>${content(o.opt("content"))}</$tag>"
    }

    /**
     * Internal links ("?query=見る&wildcards=off", maybe with "&primary_reading=") point at Yomitan's search page; the
     * page script looks them up in place. Anything else (web pages, dictionary appendices) stays inert, since
     * following it would only navigate the overlay away.
     */
    private fun link(o: JSONObject): String {
        val inner = content(o.opt("content"))
        val href = o.optString("href")
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
        "${key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }}:$value"
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
    private val SAFE_CSS = Regex("""[\w\s#%.,()+-]*""")
    private const val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"

    private const val CSS = """
body { margin: 0; background: #000; color: #eee; font: 18px/1.5 sans-serif; }
#text { position: sticky; top: 0; background: #000; padding: 8px 12px; font-size: 26px; line-height: 1.7;
    border-bottom: 1px solid #444; max-height: 35vh; overflow-y: auto; }
#text span.match { background: #2d4f6c; }
#text span.cursor { box-shadow: inset 0 -3px #6cb4ff; }
#results { padding: 0 12px 24px; }
.entry { border-bottom: 1px solid #333; padding: 10px 0; }
.head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; }
.term { font-size: 30px; }
rt { font-size: 14px; color: #aaa; }
.reasons { color: #9ab; font-size: 15px; }
.dict { margin-top: 6px; color: #8c8; font-size: 14px; }
ol, ul { margin: 2px 0; padding-left: 24px; }
li { white-space: pre-line; }
.tag { display: inline-block; background: #444; border-radius: 3px; padding: 0 5px; margin-right: 4px; font-size: 13px; }
.freq { font-size: 14px; background: #333; border-radius: 3px; padding: 0 6px; }
.freq b { font-weight: normal; color: #aaa; margin-right: 4px; }
.pitch { font-size: 16px; }
.pitch .h { border-top: 1px solid #e88; }
.pitch .d { border-right: 1px solid #e88; }
.pitch .n { color: #aaa; margin-left: 3px; font-size: 13px; }
.kanji { font-size: 48px; }
.readings { color: #ccc; }
.img { color: #aaa; font-style: italic; }
.link { text-decoration: underline; }
.link[data-q] { color: #6cb4ff; cursor: pointer; }
table { border-collapse: collapse; }
td, th { border: 1px solid #555; padding: 2px 6px; }
.message { padding: 16px 0; color: #aaa; }
"""

    private const val SCRIPT = """
const box = document.getElementById('text');
const results = document.getElementById('results');
const chars = []; // [UTF-16 offset, span], so offsets match Kotlin's String indices
for (let i = 0; i < text.length;) {
    const ch = String.fromCodePoint(text.codePointAt(i));
    if (ch === '\n') {
        box.appendChild(document.createElement('br'));
        i++;
        continue;
    }
    const span = document.createElement('span');
    span.textContent = ch;
    const at = i;
    span.onclick = () => go(at);
    box.appendChild(span);
    chars.push([i, span]);
    i += ch.length;
}
let pos = 0, len = 0; // the word picked in the captured text
// What the results show: {} for the captured-text word, {q, r} for a followed link. The history is what B returns to
// (OcrService calls back()); picking a character starts over.
let current = {};
const history = [];
function render(state) {
    const onText = state.q === undefined;
    const r = JSON.parse(onText ? OCRClip.lookup(text, pos) : OCRClip.lookupTerm(state.q, state.r));
    if (onText) len = r.length;
    for (const [i, span] of chars) {
        span.className = !onText ? '' : i === pos ? 'match cursor' : i > pos && i < pos + len ? 'match' : '';
    }
    current = state;
    results.innerHTML = r.html;
    if (onText) {
        // A long capture scrolls inside its own box (it would otherwise cover the results); keep the cursor in view.
        const cursor = chars.find(([i]) => i === pos);
        if (cursor) cursor[1].scrollIntoView({ block: 'nearest' });
    }
    window.scrollTo(0, 0);
    OCRClip.setHistory(history.length);
}
function go(offset) {
    pos = offset;
    history.length = 0;
    render({});
}
function follow(q, r) {
    history.push(current);
    render({ q, r });
}
function back() {
    if (history.length) render(history.pop());
}
results.addEventListener('click', (e) => {
    const link = e.target.closest('.link[data-q]');
    if (link) follow(link.dataset.q, link.dataset.r);
});
document.addEventListener('keydown', (e) => {
    const k = chars.findIndex(([i]) => i === pos);
    if (e.key === 'ArrowRight') {
        const next = chars.find(([i]) => i >= pos + Math.max(len, 1));
        if (next) go(next[0]);
    } else if (e.key === 'ArrowLeft') {
        if (k > 0) go(chars[k - 1][0]);
    } else {
        return;
    }
    e.preventDefault();
});
if (chars.length) go(chars[0][0]);
"""
}
