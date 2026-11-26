"""
The lookup page: the captured text, clickable per character, above the entries found from the character picked, with
the translation on top. A port of LookupPage.kt's rendering. The page's CSS and script are shared/lookup.css and
shared/lookup.js, the files the Android app shows; only a bridge shim and the translation strip are added here.
"""

import copy
import html
import json
import re
from functools import cache
from urllib.parse import unquote

from .config import SHARED

TAGS = {"ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "span", "div", "ol", "ul", "li",
        "details", "summary"}
NUMBERED = re.compile(r"^([0-9]+) ")
SAFE_CSS = re.compile(r"[\w\s#%.,()+-]*", re.ASCII)
SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"


@cache
def _shared(name: str) -> str:
    return (SHARED / name).read_text(encoding="utf-8")


# The translation strip above the captured text, both sticky together. #text loses its own stickiness for that.
DESKTOP_CSS = """
#top { position: sticky; top: 0; z-index: 1; background: #000; }
#text { position: static; }
#translation { display: none; padding: 8px 12px; border-bottom: 1px solid #444; font-size: 19px; color: #dde6ee;
    white-space: pre-wrap; max-height: 30vh; overflow-y: auto; }
#translation.on { display: block; }
#translation .note { color: #889; font-size: 13px; margin-top: 4px; }
#translation .note:empty { display: none; }
#translation a { color: #6cb4ff; cursor: pointer; text-decoration: underline; }
"""

# OCRClip as the Android WebView's JavaScript bridge offers it, synchronous like @JavascriptInterface: each call is a
# blocking request to the app's ocrclip:// scheme handler.
SHIM = """
let ankiWarned = false;
const OCRClip = (() => {
    function call(method, args) {
        const x = new XMLHttpRequest();
        x.open('GET', '/api/' + method + '?q=' + encodeURIComponent(JSON.stringify(args)), false);
        x.send();
        return x.responseText;
    }
    return {
        lookup: (text, offset) => call('lookup', { text, offset }),
        lookupTerm: (term, reading) => call('lookupTerm', { term, reading }),
        addToAnki: (entry, definition, sense, offset) => call('addToAnki', { entry, definition, sense, offset }),
        // The shared script expects a JSON array. Why Anki couldn't be asked is shown once per capture, not on
        // every lookup.
        ankiExisting: () => {
            const r = JSON.parse(call('ankiExisting', {}));
            if (r.error && !ankiWarned) {
                ankiWarned = true;
                showNotice(r.error);
            }
            return JSON.stringify(r.existing);
        },
        setHistory: (depth) => { call('setHistory', { depth }); },
        open: (url) => { call('open', { url }); },
        translate: () => { call('translate', {}); },
    };
})();
const translation = document.getElementById('translation');
const translationText = translation.querySelector('.text');
const translationNote = translation.querySelector('.note');
function setTranslation(t) { translationText.textContent = t; translation.classList.add('on'); }
function setNote(n) { translationNote.textContent = n; translation.classList.add('on'); }
function setTranslateLink(label) {
    translationText.textContent = '';
    translationNote.textContent = '';
    const a = document.createElement('a');
    a.textContent = label;
    a.onclick = () => OCRClip.translate();
    translationText.appendChild(a);
    translation.classList.add('on');
}
function setTranslator(url, label) {
    translationText.textContent = '';
    const a = document.createElement('a');
    a.textContent = label;
    a.onclick = () => OCRClip.open(url);
    translationText.appendChild(a);
    translation.classList.add('on');
}
"""


def page(text: str, font: str = "") -> str:
    # Only kana, kanji and full-width forms, so English glosses keep the default Latin font.
    font_css = "" if not font else f"""
@font-face {{ font-family: OCRClipJa; src: local({json.dumps(font)});
    unicode-range: U+3000-30FF, U+3400-4DBF, U+4E00-9FFF, U+F900-FAFF, U+FF00-FFEF, U+20000-2FFFF; }}
body {{ font-family: OCRClipJa, sans-serif; }}
"""
    return f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>{_shared("lookup.css")}{DESKTOP_CSS}{font_css}</style></head>
<body><div id="top"><div id="translation"><div class="text"></div><div class="note"></div></div><div id="text"></div></div><div id="results"></div>
<script>
const text = {json.dumps(text, ensure_ascii=False).replace("</", "<\\/")};
{SHIM}
{_shared("lookup.js")}
</script></body></html>"""


def message(text: str) -> str:
    return f'<div class="message">{esc(text)}</div>'


def esc(s: str) -> str:
    return html.escape(s, quote=True).replace("&#x27;", "&#39;")


def _str(v) -> str:
    """A JSON value as org.json's toString would write it."""
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    return str(v)


def _opt_str(o: dict, key: str) -> str:
    v = o.get(key)
    return "" if v is None else _str(v)


def results(result, anki: bool = False, styles: dict | None = None) -> str:
    """[anki] adds a + to each definition, and one to each sense of a definition that has several (see sense_count)."""
    styles = styles or {}
    if result.message:
        return message(result.message)
    if not result.entries and not result.kanji:
        return message("No entries")
    out = []
    for ei, e in enumerate(result.entries):
        out.append('<div class="entry"><div class="head">')
        if e.reading and e.reading != e.term:
            out.append(f'<ruby class="term">{esc(e.term)}<rt>{esc(e.reading)}</rt></ruby>')
        else:
            out.append(f'<span class="term">{esc(e.term)}</span>')
        for p in e.pitches:
            for position in p.positions:
                out.append(_pitch(e.reading, position, p.dictionary))
        for f in e.frequencies:
            out.append(f'<span class="freq"><b>{esc(f.dictionary)}</b>{esc(f.value)}</span>')
        out.append("</div>")
        if e.reasons:
            out.append(f'<div class="reasons">« {" « ".join(esc(r) for r in e.reasons)}</div>')
        dictionary = None
        for di, d in enumerate(e.definitions):
            if d.dictionary != dictionary:
                if dictionary is not None:
                    out.append("</ol>")
                out.append(f'<div class="dict">{esc(d.dictionary)}</div><ol data-dictionary="{esc(d.dictionary)}">')
                dictionary = d.dictionary
            out.append("<li>")
            if anki:
                out.append(f'<span class="add" data-e="{ei}" data-d="{di}">+</span>')
            for t in d.tags:
                out.append(f'<span class="tag" title="{esc(t.notes)}">{esc(t.name)}</span>')
            senses = _Senses(ei, di) if anki and sense_count(d.glossary) >= 2 else None
            out.append(_glossary(d.glossary, senses))
            out.append("</li>")
        if dictionary is not None:
            out.append("</ol>")
        out.append("</div>")
    for k in result.kanji:
        out.append(f'<div class="entry"><div class="head"><span class="kanji">{esc(k.character)}</span></div>')
        out.append(f'<div class="dict">{esc(k.dictionary)}</div>')
        if k.onyomi.strip():
            out.append(f'<div class="readings">{esc(k.onyomi)}</div>')
        if k.kunyomi.strip():
            out.append(f'<div class="readings">{esc(k.kunyomi)}</div>')
        out.append("<ol>" + "".join(f"<li>{esc(m)}</li>" for m in k.meanings) + "</ol></div>")
    titles = list(dict.fromkeys(d.dictionary for e in result.entries for d in e.definitions))
    css = [scope_css(styles[t], t, ".entry") for t in titles if t in styles]
    if css:
        out.append(f"<style>{''.join(css)}</style>")
    return "".join(out)


def _glossary(items: list, senses) -> str:
    parts = [p for p in (_item(i, False, senses) for i in items) if p]
    return parts[0] if len(parts) == 1 else '<ul class="gloss">' + "".join(f"<li>{p}</li>" for p in parts) + "</ul>"


def anki_glossary(definitions: list, styles: dict, single: bool = False) -> str:
    """
    [definitions] as Yomitan writes its {glossary} into Anki fields (gloss-sc-* classes, data-sc-* attributes), each
    dictionary's styles.css scoped after them. [single]: the first definition alone, like Yomitan's brief glossary.
    """
    out = ['<div class="yomitan-glossary">']
    if single:
        if definitions:
            out.append(_anki_definition(definitions[0], styles, True))
    else:
        out.append(f'<ol data-count="{len(definitions)}">')
        out.extend(_anki_definition(d, styles, False) for d in definitions)
        out.append("</ol>")
    out.append("</div>")
    titles = list(dict.fromkeys(d.dictionary for d in definitions))
    css = [scope_css(styles[t], t) for t in titles if t in styles]
    if css:
        out.append(f"<style>{''.join(css)}</style>")
    return "".join(out)


def _anki_definition(d, styles: dict, single: bool) -> str:
    tag = "div" if single else "li"
    styled = ' data-has-styles=""' if d.dictionary in styles else ""
    out = [f'<{tag} data-dictionary="{esc(d.dictionary)}"{styled}>']
    # Like Yomitan's brief glossary, a lone definition goes without its tags (★, priority form) and its name.
    if not single:
        for t in d.tags:
            out.append(f'<span class="gloss-tag" data-category="{esc(t.category)}" title="{esc(t.notes)}">')
            out.append(esc(t.name).replace(" ", "&nbsp;") + "</span>")
        out.append(f"<i>({esc(d.dictionary)})</i>")
    items = [p for p in (_item(i, True) for i in d.glossary) if p]
    if single and len(items) == 1:
        out.append(items[0])
    else:
        out.append(f'<ul class="gloss-list" data-count="{len(items)}">')
        out.extend(f'<li class="gloss-item"><div class="gloss-content">{i}</div></li>' for i in items)
        out.append("</ul>")
    out.append(f"</{tag}>")
    return "".join(out)


def scope_css(css: str, title: str, root: str = ".yomitan-glossary") -> str:
    """Every selector of a dictionary's styles.css prefixed with `[root] [data-dictionary="title"]`, as Yomitan does."""
    prefix = f'{root} [data-dictionary="{title.replace(chr(92), chr(92) * 2).replace(chr(34), chr(92) + chr(34))}"]'
    # No "<" can end the <style> element early; CSS doesn't need it outside strings.
    return _scope(re.sub(r"/\*[\s\S]*?\*/", "", css).replace("<", ""), prefix)


def _scope(css: str, prefix: str) -> str:
    out = []
    i = 0
    while True:
        start = css.find("{", i)
        if start < 0:
            break
        depth = 0
        close = start
        while close < len(css):
            if css[close] == "{":
                depth += 1
            if css[close] == "}":
                depth -= 1
                if depth == 0:
                    break
            close += 1
        # Anything before the last ';' is a statement such as @charset or @import, which doesn't belong in a field.
        prelude = css[i:start].rsplit(";", 1)[-1].strip()
        block = css[start + 1:min(close, len(css))]
        if prelude.startswith(("@media", "@supports")):
            out.append(f"{prelude} {{{_scope(block, prefix)}}}")
        elif prelude.startswith("@"):
            out.append(f"{prelude} {{{block}}}")
        elif prelude:
            out.append(", ".join(f"{prefix} {s}" for s in _selectors(prelude)) + f" {{{block.strip()}}}\n")
        i = close + 1
    return "".join(out)


def _selectors(selector_list: str) -> list[str]:
    """A selector list split at its top-level commas, so :is(a, b) stays whole."""
    out = []
    depth = start = 0
    for i, ch in enumerate(selector_list):
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "," and depth == 0:
            out.append(selector_list[start:i].strip())
            start = i + 1
    out.append(selector_list[start:].strip())
    return [s for s in out if s]


class _Senses:
    """Gives out the + buttons of one definition's senses, numbered in the order sense_count and only_sense use."""

    def __init__(self, entry: int, definition: int):
        self.entry, self.definition, self.next = entry, definition, 0

    def button(self) -> str:
        b = f'<span class="add sense" data-e="{self.entry}" data-d="{self.definition}" data-s="{self.next}">+</span>'
        self.next += 1
        return b


def sense_count(glossary: list) -> int:
    """Senses in document order: data-content="sense" elements (Jitendex) and numbered lines (新和英)."""
    total = 0
    for g in glossary:
        text = _plain_text(g)
        if text is not None:
            total += len(sense_lines(text) or [])
        elif isinstance(g, dict) and g.get("type") == "structured-content":
            total += _structured_senses(g.get("content"))
    return total


def only_sense(glossary: list, index: int) -> list | None:
    """A copy of [glossary] with only sense [index] (see LookupPage.onlySense); None if there's no such sense."""
    glossary = copy.deepcopy(glossary)
    out = []
    state = {"next": 0, "found": False}

    def keep(node) -> bool:
        if isinstance(node, list):
            node[:] = [n for n in node if keep(n)]
            return True
        if not isinstance(node, dict):
            return True
        if _is_sense(node):
            hit = state["next"] == index
            state["next"] += 1
            if hit:
                state["found"] = True
            return hit
        if node.get("tag") == "a" or _structured_senses(node.get("content")) == 0:
            return True
        return keep(node.get("content")) and _structured_senses(node.get("content")) > 0

    for g in glossary:
        text = _plain_text(g)
        lines = sense_lines(text) if text is not None else None
        if text is not None and lines is not None:
            k = index - state["next"]
            state["next"] += len(lines)
            if not 0 <= k < len(lines):
                continue
            state["found"] = True
            all_lines = text.split("\n")
            kept = "\n".join(all_lines[:lines[0][0]] + all_lines[lines[k][0]:lines[k][1]])
            if isinstance(g, dict):
                g["text"] = kept
                out.append(g)
            else:
                out.append(kept)
        elif isinstance(g, dict) and g.get("type") == "structured-content" and _structured_senses(g.get("content")) > 0:
            if keep(g.get("content")) and _structured_senses(g.get("content")) > 0:
                out.append(g)
        else:
            out.append(g)
    return out if state["found"] else None


def _plain_text(g) -> str | None:
    if isinstance(g, str):
        return g
    if isinstance(g, dict) and g.get("type") == "text":
        return _opt_str(g, "text")
    return None


def _structured_senses(node) -> int:
    # A sense inside a sense isn't counted, and links don't get buttons, the same way _element renders them.
    if isinstance(node, list):
        return sum(_structured_senses(n) for n in node)
    if isinstance(node, dict):
        if _is_sense(node):
            return 1
        if node.get("tag") == "a":
            return 0
        return _structured_senses(node.get("content"))
    return 0


def _is_sense(o: dict) -> bool:
    data = o.get("data")
    return o.get("tag") in TAGS and isinstance(data, dict) and data.get("content") == "sense"


def sense_lines(text: str) -> list[tuple[int, int]] | None:
    """新和英's senses as [start, end) line ranges (see LookupPage.senseLines); None unless there are at least two."""
    lines = text.split("\n")
    starts = []
    for i in range(1, len(lines)):
        m = NUMBERED.match(lines[i])
        if not m:
            continue
        if int(m.group(1)) != len(starts) + 1:
            break
        starts.append(i)
    if len(starts) < 2:
        return None
    end = starts[-1] + 1
    while end < len(lines) and lines[end].startswith("〜"):
        end += 1
    return [(s, starts[k + 1] if k + 1 < len(starts) else end) for k, s in enumerate(starts)]


def _plain(text: str, anki: bool, senses) -> str:
    """Plain text on the page, with a + before each sense when [senses] is given; on a card, newlines become <br>."""
    if anki:
        return esc(text.rstrip("\n")).replace("\n", "<br>")
    ranges = sense_lines(text) if senses else None
    if not ranges:
        return esc(text)
    lines = text.split("\n")
    out = [esc("\n".join(lines[:ranges[0][0]]))]
    for start, end in ranges:
        out.append('<div class="sense-add">' + senses.button() + esc("\n".join(lines[start:end])) + "</div>")
    out.append(esc("\n".join(lines[ranges[-1][1]:])))
    return "".join(out)


def _item(item, anki: bool, senses=None) -> str:
    if isinstance(item, str):
        return _plain(item, anki, senses)
    if isinstance(item, dict):
        kind = item.get("type")
        if kind == "text":
            return _plain(_opt_str(item, "text"), anki, senses)
        if kind == "structured-content":
            return _content(item.get("content"), anki, senses)
        if kind == "image":
            return _image(item)
    return ""


def _image(o: dict) -> str:
    # build_dict.py doesn't keep dictionary images, so their description stands in for them.
    alt = _opt_str(o, "alt") or _opt_str(o, "description")
    return f'<span class="img">{esc(alt)}</span>' if alt else ""


def _content(node, anki: bool, senses=None) -> str:
    """Yomitan structured content, limited to its own tag set so a dictionary can't inject scripts or real links."""
    if isinstance(node, str):
        return esc(node)
    if isinstance(node, list):
        return "".join(_content(n, anki, senses) for n in node)
    if isinstance(node, dict):
        return _element(node, anki, senses)
    return ""


def _kebab(name: str) -> str:
    return re.sub("[A-Z]", lambda m: "-" + m.group(0).lower(), name)


def _element(o: dict, anki: bool, senses) -> str:
    tag = _opt_str(o, "tag")
    if tag == "br":
        return "<br>"
    if tag == "img":
        return _image(o)
    if tag == "a":
        return _link(o, anki)
    if tag not in TAGS:
        return _content(o.get("content"), anki, senses)
    sense = senses is not None and _is_sense(o)
    attrs = [f' class="gloss-sc-{tag}{" sense-add" if sense else ""}"']
    data = o.get("data")
    if isinstance(data, dict):
        for key, value in data.items():
            if not all(c.isalnum() or c == "-" for c in key):
                continue
            # Yomitan sets element.dataset["sc" + Key], so "Contents" and "contents" both become data-sc-contents.
            name = _kebab("sc" + key[:1].upper() + key[1:])
            attrs.append(f' data-{name}="{esc(_str(value))}"')
    style = o.get("style")
    if isinstance(style, dict):
        css = _style(style)
        if css:
            attrs.append(f' style="{esc(css)}"')
    for key in ("title", "lang"):
        value = _opt_str(o, key)
        if value:
            attrs.append(f' {key}="{esc(value)}"')
    for key, name in (("colSpan", "colspan"), ("rowSpan", "rowspan")):
        if key in o:
            try:
                attrs.append(f' {name}="{int(o[key])}"')
            except (TypeError, ValueError):
                attrs.append(f' {name}="1"')
    if tag == "details" and o.get("open") is True:
        attrs.append(" open")
    button = senses.button() if sense else ""
    return f"<{tag}{''.join(attrs)}>{button}{_content(o.get('content'), anki, None if sense else senses)}</{tag}>"


def _link(o: dict, anki: bool) -> str:
    """Internal links (?query=…) are looked up in place by the page script; anything else stays inert."""
    inner = _content(o.get("content"), anki)
    href = _opt_str(o, "href")
    if anki:
        # On a card a web link can open; an internal one would lead nowhere, so it keeps only the look of a link.
        web = href.startswith(("https://", "http://"))
        return f'<a class="gloss-sc-a"{f" href={chr(34)}{esc(href)}{chr(34)}" if web else ""}>{inner}</a>'
    if not href.startswith("?"):
        return f'<span class="link">{inner}</span>'
    params = {}
    for part in href[1:].split("&"):
        key, _, value = part.partition("=")
        params[key] = unquote(value)
    query = params.get("query", "")
    if not query:
        return f'<span class="link">{inner}</span>'
    reading = params.get("primary_reading", "")
    return f'<span class="link" data-q="{esc(query)}" data-r="{esc(reading)}">{inner}</span>'


def _style(style: dict) -> str:
    out = []
    for key, v in style.items():
        if not key.isalpha():
            continue
        if isinstance(v, bool):
            continue
        if isinstance(v, (int, float)):
            value = f"{v}em"  # Yomitan's unit for numeric margins and padding
        elif isinstance(v, str):
            value = v
        elif isinstance(v, list):
            value = " ".join("" if x is None else _str(x) for x in v)
        else:
            continue
        if not SAFE_CSS.fullmatch(value) or "url" in value.lower():
            continue
        out.append(f"{_kebab(key)}:{value}")
    return ";".join(out)


def _pitch(reading: str, position: str, dictionary: str) -> str:
    """Reading with the pitch accent drawn over it: a line over high morae and a tick at the drop."""
    title = esc(dictionary)
    try:
        drop = int(position)
    except ValueError:
        return f'<span class="pitch" title="{title}">{esc(position)}</span>'
    morae = []
    for ch in reading:
        if ch in SMALL_KANA and morae:
            morae[-1] += ch
        else:
            morae.append(ch)
    parts = []
    for i, mora in enumerate(morae):
        high = i > 0 if drop == 0 else i == 0 if drop == 1 else 1 <= i < drop
        classes = " ".join(c for c in ("h" if high else None, "d" if i == drop - 1 else None) if c)
        parts.append(f'<span class="{classes}">{esc(mora)}</span>')
    return f'<span class="pitch" title="{title}">{"".join(parts)}<span class="n">[{drop}]</span></span>'
