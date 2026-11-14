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
    /**
     * [fontVersion] is set when a font is loaded for Japanese text (served by the overlay at /font); it changes with
     * the file so the WebView doesn't reuse an old one. lang="ja" picks Japanese glyphs (直, 骨…) from CJK fonts that
     * would otherwise follow the system locale and draw the Chinese forms.
     */
    fun page(text: String, fontVersion: String? = null, panel: Boolean = false) = """<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>$CSS${fontVersion?.let { fontCss(it) } ?: ""}${if (panel) PANEL_CSS else ""}</style></head>
<body><div id="text"></div><div id="results"></div>${if (panel) "<button id=\"back\" aria-label=\"Back\">‹</button>" else ""}
<script>
const text = ${JSONObject.quote(text)};
$SCRIPT
</script></body></html>"""

    // Only kana, kanji and full-width forms, so English glosses keep the system's Latin font.
    private fun fontCss(version: String) = """
@font-face { font-family: OCRClipJa; src: url('/font?v=$version');
    unicode-range: U+3000-30FF, U+3400-4DBF, U+4E00-9FFF, U+F900-FAFF, U+FF00-FFEF, U+20000-2FFFF; }
body { font-family: OCRClipJa, sans-serif; }
"""

    fun message(text: String) = "<div class=\"message\">${esc(text)}</div>"

    /**
     * [anki] adds a + button to each definition, which the page script sends to `OCRClip.addToAnki`. Each dictionary's
     * [styles] (its styles.css) applies to its own entries, as in Yomitan.
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
            val css = result.entries.flatMap { e -> e.definitions.map { it.dictionary } }.distinct()
                .mapNotNull { title -> styles[title]?.let { scopeCss(it, title, ".entry") } }
            if (css.isNotEmpty()) append("<style>${css.joinToString("")}</style>")
        }
    }

    private fun glossary(items: JSONArray): String {
        val parts = (0 until items.length()).map { item(items.opt(it), false) }.filter { it.isNotEmpty() }
        return if (parts.size == 1) parts[0] else "<ul class=\"gloss\">${parts.joinToString("") { "<li>$it</li>" }}</ul>"
    }

    /**
     * [definitions] as Yomitan writes its {glossary} into Anki fields: the same markup, class names (gloss-sc-*) and
     * data-sc-* attributes, so note types made for Yomitan (Lapis) style them the same way. Each dictionary's
     * styles.css follows in a <style>, scoped to that dictionary's entries.
     */
    fun ankiGlossary(definitions: List<LocalDictionary.Definition>, styles: Map<String, String>): String = buildString {
        append("<div class=\"yomitan-glossary\"><ol data-count=\"${definitions.size}\">")
        for (d in definitions) {
            val styled = d.dictionary in styles
            append("<li data-dictionary=\"${esc(d.dictionary)}\"${if (styled) " data-has-styles=\"\"" else ""}>")
            for (t in d.tags) {
                append("<span class=\"gloss-tag\" data-category=\"${esc(t.category)}\" title=\"${esc(t.notes)}\">")
                append(esc(t.name).replace(" ", "&nbsp;")).append("</span>")
            }
            append("<i>(${esc(d.dictionary)})</i>")
            val items = (0 until d.glossary.length()).map { item(d.glossary.opt(it), true) }.filter { it.isNotEmpty() }
            append("<ul class=\"gloss-list\" data-count=\"${items.size}\">")
            for (it in items) append("<li class=\"gloss-item\"><div class=\"gloss-content\">$it</div></li>")
            append("</ul></li>")
        }
        append("</ol></div>")
        val css = definitions.map { it.dictionary }.distinct().mapNotNull { title -> styles[title]?.let { scopeCss(it, title) } }
        if (css.isNotEmpty()) append("<style>${css.joinToString("")}</style>")
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

    private fun item(item: Any?, anki: Boolean): String = when (item) {
        is String -> esc(item)
        is JSONObject -> when (item.optString("type")) {
            "text" -> esc(item.optString("text"))
            "structured-content" -> content(item.opt("content"), anki)
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
     * only changes how links are written.
     */
    private fun content(node: Any?, anki: Boolean): String = when (node) {
        is String -> esc(node)
        is JSONArray -> (0 until node.length()).joinToString("") { content(node.opt(it), anki) }
        is JSONObject -> element(node, anki)
        else -> ""
    }

    private fun element(o: JSONObject, anki: Boolean): String {
        val tag = o.optString("tag")
        when (tag) {
            "br" -> return "<br>"
            "img" -> return image(o)
            "a" -> return link(o, anki)
            !in TAGS -> return content(o.opt("content"), anki)
        }
        val attrs = StringBuilder(" class=\"gloss-sc-$tag\"")
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
        return "<$tag$attrs>${content(o.opt("content"), anki)}</$tag>"
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
.entry > ol > li { clear: right; } /* a one-line definition is shorter than its + button */
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
.add { float: right; min-width: 40px; height: 32px; margin: 0 0 4px 10px; border-radius: 16px; background: #2d4f6c;
    color: #fff; text-align: center; font-size: 22px; line-height: 32px; cursor: pointer; white-space: nowrap; }
.add.busy { opacity: 0.6; }
.add.done { background: #2e5d3a; }
.add.dup { background: #2e5d3a; opacity: 0.75; }
.add.armed { background: #8a6418; opacity: 1; }
#notice { position: fixed; left: 0; right: 0; bottom: 16px; margin: 0 auto; width: fit-content; max-width: 90%; padding: 8px 16px;
    border-radius: 18px; background: #333; color: #fff; font-size: 18px; text-align: center; opacity: 0;
    transition: opacity 0.2s; pointer-events: none; }
#notice.on { opacity: 0.95; }
table { border-collapse: collapse; }
td, th { border: 1px solid #555; padding: 2px 6px; }
.message { padding: 16px 0; color: #aaa; }
"""

    /**
     * On the second screen no key reaches the page, so a ‹ chip steps back after following links. It sits left of the
     * window's ✕ (36 dp, 6 dp from the corner), and the captured text keeps clear of both.
     */
    private const val PANEL_CSS = """
#text { padding-right: 96px; }
#back { position: fixed; top: 6px; right: 48px; width: 36px; height: 36px; padding: 0; border: 0; border-radius: 50%;
    background: rgba(34, 34, 34, 0.8); color: #aaa; font-size: 26px; line-height: 34px; display: none; z-index: 1; }
#back.on { display: block; }
"""

    private const val SCRIPT = """
const box = document.getElementById('text');
const backChip = document.getElementById('back'); // second screen only
if (backChip) backChip.onclick = () => back();
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
// (OcrService calls back()), or the ‹ chip on the second screen; picking a character starts over.
let current = {};
const history = [];
let renders = 0;
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
    if (backChip) backChip.classList.toggle('on', history.length > 0);
    const n = ++renders;
    // Asking AnkiDroid can take a moment, so the entries show first and get their marks after.
    setTimeout(() => {
        if (n !== renders || !results.querySelector('.add')) return;
        for (const e of JSON.parse(OCRClip.ankiExisting())) markAdded(e);
    }, 0);
}
// A short message over the page: the service's toasts don't show when OCR Clip's notifications are off.
const notice = document.createElement('div');
notice.id = 'notice';
document.body.appendChild(notice);
function showNotice(message) {
    notice.textContent = message;
    notice.classList.add('on');
    clearTimeout(notice.hide);
    notice.hide = setTimeout(() => notice.classList.remove('on'), 2500);
}
// An entry already in Anki: its + buttons become ✓, and adding another note takes a second tap.
function markAdded(e) {
    for (const b of results.querySelectorAll('.add[data-e="' + e + '"]')) {
        if (b.classList.contains('done')) continue;
        b.classList.add('dup');
        b.textContent = '✓';
    }
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
    const add = e.target.closest('.add');
    if (add) {
        if (add.classList.contains('busy') || add.classList.contains('done')) return;
        if (add.classList.contains('dup') && !add.classList.contains('armed')) {
            add.classList.add('armed');
            add.textContent = '+?';
            showNotice('Already in Anki. Tap again to add another');
            clearTimeout(add.disarm);
            add.disarm = setTimeout(() => {
                add.classList.remove('armed');
                if (add.classList.contains('dup')) add.textContent = '✓';
            }, 3000);
            return;
        }
        clearTimeout(add.disarm);
        add.classList.remove('dup', 'armed');
        add.classList.add('busy');
        add.textContent = '…';
        const offset = current.q === undefined ? pos : -1; // where the word is in the captured text
        // Let the dots paint before the bridge call holds up the page.
        setTimeout(() => {
            const error = OCRClip.addToAnki(+add.dataset.e, +add.dataset.d, offset);
            add.classList.remove('busy');
            if (error) {
                add.textContent = '+';
                showNotice(error);
            } else {
                add.classList.add('done');
                add.textContent = '✓';
                markAdded(add.dataset.e);
            }
        }, 30);
        return;
    }
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
