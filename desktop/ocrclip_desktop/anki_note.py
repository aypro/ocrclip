"""
Field values for an Anki note from one looked-up entry, matched by field name the way Yomitan's Lapis setup fills
them; a port of AnkiNote.kt.
"""

import re
from dataclasses import dataclass

from . import lookup_page


@dataclass
class Source:
    entry: object
    definition: object
    text: str  # the captured text
    offset: int  # the word's position in it, or -1 when the entry came from a followed link
    picture: str | None  # stored media file names
    audio: str | None
    styles: dict


_NAMES = {
    "term": {"expression", "word", "term", "vocab", "vocabulary", "front", "key"},
    "reading": {"expressionreading", "reading", "kana", "wordreading", "vocabkana"},
    "furigana": {"expressionfurigana", "furigana", "wordfurigana", "vocabfurigana"},
    "definition": {"maindefinition", "definition", "meaning", "back", "glossaryfirst"},
    "glossary": {"glossary", "definitions"},
    "sentence": {"sentence"},
    "picture": {"picture", "image", "screenshot"},
    "audio": {"expressionaudio", "audio", "wordaudio", "vocabaudio", "termaudio"},
    "pitch": {"pitchposition", "pitch", "pitchaccent"},
    "frequency": {"frequency", "frequencies"},
    "freq_sort": {"freqsort", "frequencysort"},
}
_BY_NAME = {name: value for value, names in _NAMES.items() for name in names}


def _value_of(field: str):
    return _BY_NAME.get("".join(c for c in field.lower() if "a" <= c <= "z"))


def wants_picture(fields: list) -> bool:
    return any(_value_of(f) == "picture" for f in fields)


def wants_audio(fields: list) -> bool:
    return any(_value_of(f) == "audio" for f in fields)


def term_field(fields: list) -> str:
    """The field the term goes in, which the duplicate check searches."""
    return next((f for f in fields if _value_of(f) == "term"), fields[0])


def fields(names: list, s: Source) -> dict:
    out = {}
    e = s.entry
    for i, name in enumerate(names):
        kind = _value_of(name)
        if kind == "term":
            value = e.term
        elif kind == "reading":
            value = e.reading
        elif kind == "furigana":
            value = furigana(e.term, e.reading)
        elif kind == "definition":
            value = lookup_page.anki_glossary([s.definition], s.styles, single=True)
        elif kind == "glossary":
            value = lookup_page.anki_glossary(e.definitions, s.styles)
        elif kind == "sentence":
            value = _sentence(s)
        elif kind == "picture":
            value = f'<img src="{_esc(s.picture)}">' if s.picture else ""
        elif kind == "audio":
            value = f"[sound:{_esc(s.audio)}]" if s.audio else ""
        elif kind == "pitch":
            positions = list(dict.fromkeys(p for pitch in e.pitches for p in pitch.positions))
            value = "".join(positions) if len(positions) <= 1 else \
                "<ol>" + "".join(f"<li>{_esc(p)}</li>" for p in positions) + "</ol>"
        elif kind == "frequency":
            value = "" if not e.frequencies else \
                "<ul>" + "".join(f"<li>{_esc(f.dictionary)}: {_esc(f.value)}</li>" for f in e.frequencies) + "</ul>"
        elif kind == "freq_sort":
            value = str(_harmonic_rank(e))
        else:
            # Anki sorts and checks duplicates by the first field, so a note type without a known one gets the word.
            value = e.term if i == 0 else ""
        out[name] = value
    return out


def _sentence(s: Source) -> str:
    """The captured text with the word in bold, as Lapis expects."""
    text, start = s.text, s.offset
    end = min(start + s.entry.length, len(text))
    if start < 0 or start >= end:
        html = _esc(text)
    else:
        html = _esc(text[:start]) + "<b>" + _esc(text[start:end]) + "</b>" + _esc(text[end:])
    return html.replace("\n", "<br>")


def _harmonic_rank(entry) -> int:
    """Yomitan's frequency-harmonic-rank: harmonic mean of each frequency dictionary's first number, or 9999999."""
    by_dictionary = {}
    for f in entry.frequencies:
        by_dictionary.setdefault(f.dictionary, []).append(f.value)
    ranks = []
    for values in by_dictionary.values():
        for v in values:
            m = re.search(r"[0-9]+", v)
            if m and float(m.group(0)) > 0:
                ranks.append(float(m.group(0)))
                break
    if not ranks:
        return 9999999
    return int(len(ranks) / sum(1 / r for r in ranks))


def _is_kana(ch: str) -> bool:
    return "ぁ" <= ch <= "ゖ" or "ァ" <= ch <= "ヺ" or ch in "ーゝゞ"


def hiragana(s: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def furigana(term: str, reading: str) -> str:
    """Anki furigana split over the kanji runs, like Yomitan's furigana-plain: 取り消す/とりけす → 取[と]り 消[け]す."""
    if not reading or hiragana(term) == hiragana(reading):
        return term
    groups = []  # [kana, text]
    for ch in term:
        kana = _is_kana(ch)
        if groups and groups[-1][0] == kana:
            groups[-1][1] += ch
        else:
            groups.append([kana, ch])
    pattern = "".join(re.escape(hiragana(text)) if kana else "(.+?)" for kana, text in groups)
    match = re.fullmatch(pattern, hiragana(reading))
    if not match:
        return f"{term}[{reading}]"
    out = ""
    group = 1
    for kana, text in groups:
        if kana:
            out += text
        else:
            # Furigana applies back to the last space, so each kanji run after other text needs one before it.
            if out:
                out += " "
            start, end = match.span(group)
            group += 1
            out += f"{text}[{reading[start:end]}]"
    return out


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
