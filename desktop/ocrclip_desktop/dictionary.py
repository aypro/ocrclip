"""
Offline lookup in Yomitan dictionaries, from the database tools/build_dict.py builds; a port of LocalDictionary.kt.
Text positions here are Python string indices (code points); the page's are UTF-16, and bridge.py converts.
"""

import json
import sqlite3
import threading
import unicodedata
import zlib
from bisect import bisect_right
from dataclasses import dataclass, field
from pathlib import Path

from .deinflector import Deinflector, matches

SCHEMA_VERSION = 3  # tools/build_dict.py SCHEMA_VERSION
# Schema 2 only lacks dictionaries.styles, so it still opens; Anki notes then come without dictionary CSS.
MIN_SCHEMA_VERSION = 2
MAX_LENGTH = 16
MAX_ENTRIES = 32
# SQLite's default limit on bound parameters is higher, but this keeps the chunks the same as on Android.
MAX_PARAMS = 999


@dataclass
class Info:
    id: int
    title: str
    terms: int
    meta: int
    kanji: int
    enabled: bool


@dataclass
class Tag:
    name: str
    category: str
    notes: str


@dataclass
class Definition:
    dictionary: str
    tags: list
    glossary: list  # parsed glossary JSON


@dataclass
class Frequency:
    dictionary: str
    value: str


@dataclass
class Pitch:
    dictionary: str
    positions: list


@dataclass
class Entry:
    """[length]: how much of the scanned text this entry's term covers."""
    term: str
    reading: str
    reasons: list
    definitions: list
    frequencies: list
    pitches: list
    length: int


@dataclass
class Kanji:
    dictionary: str
    character: str
    onyomi: str
    kunyomi: str
    meanings: list


@dataclass
class Result:
    """[length]: how much of the text from the offset the longest entry covers, for highlighting."""
    length: int
    entries: list = field(default_factory=list)
    kanji: list = field(default_factory=list)
    message: str | None = None


# Han, Hiragana and Katakana as Unicode's Scripts.txt has them (what Java's UnicodeScript and the page's
# \p{Script=…} use), plus the long vowel marks, sound marks and full-width letters and digits. Keep in step with
# LocalDictionary.isJapanese and the JAPANESE regex in the page script.
_HAN = [
    (0x2E80, 0x2E99), (0x2E9B, 0x2EF3), (0x2F00, 0x2FD5), (0x3005, 0x3005), (0x3007, 0x3007), (0x3021, 0x3029),
    (0x3038, 0x303B), (0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFA6D), (0xFA70, 0xFAD9), (0x16FE2, 0x16FE3),
    (0x16FF0, 0x16FF1), (0x20000, 0x2A6DF), (0x2A700, 0x2EBEF), (0x2EBF0, 0x2EE5D), (0x2F800, 0x2FA1D),
    (0x30000, 0x323AF),
]
_RANGES = sorted(_HAN + [
    # Hiragana
    (0x3041, 0x3096), (0x309D, 0x309F), (0x1B001, 0x1B11F), (0x1B132, 0x1B132), (0x1B150, 0x1B152),
    (0x1F200, 0x1F200),
    # Katakana
    (0x30A1, 0x30FA), (0x30FD, 0x30FF), (0x31F0, 0x31FF), (0x32D0, 0x32FE), (0x3300, 0x3357), (0xFF66, 0xFF6F),
    (0xFF71, 0xFF9D), (0x1AFF0, 0x1AFF3), (0x1AFF5, 0x1AFFB), (0x1AFFD, 0x1AFFE), (0x1B000, 0x1B000),
    (0x1B120, 0x1B122), (0x1B155, 0x1B155), (0x1B164, 0x1B167),
    # ー ｰ ゛゜ ﾞﾟ ０-９ Ａ-Ｚ ａ-ｚ
    (0x30FC, 0x30FC), (0xFF70, 0xFF70), (0x309B, 0x309C), (0xFF9E, 0xFF9F), (0xFF10, 0xFF19), (0xFF21, 0xFF3A),
    (0xFF41, 0xFF5A),
])
_STARTS = [r[0] for r in _RANGES]
_HAN_STARTS = [r[0] for r in _HAN]


def _within(ranges, starts, ch: str) -> bool:
    cp = ord(ch)
    i = bisect_right(starts, cp) - 1
    return i >= 0 and cp <= ranges[i][1]


def is_japanese(ch: str) -> bool:
    return _within(_RANGES, _STARTS, ch)


def is_han(ch: str) -> bool:
    return _within(_HAN, _HAN_STARTS, ch)


def _shift_kana(text: str, lo: str, hi: str, by: int) -> str:
    return "".join(chr(ord(c) + by) if lo <= c <= hi else c for c in text)


def variants(raw: str) -> list[str]:
    """Like Yomitan's text preprocessors: width and compatibility forms folded, and both kana scripts tried."""
    text = unicodedata.normalize("NFKC", raw)
    out = [text, _shift_kana(text, "ァ", "ヶ", ord("ぁ") - ord("ァ")), _shift_kana(text, "ぁ", "ゖ", ord("ァ") - ord("ぁ"))]
    return list(dict.fromkeys(out))


def _frequency(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return str(int(value)) if float(value).is_integer() else str(value)
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return value.get("displayValue") or _frequency(value.get("value"))
    return None


class LocalDictionary:
    def __init__(self, config):
        self.config = config
        self.deinflector = Deinflector()
        self.lock = threading.RLock()
        self.db = None
        self.opened = None  # (path, mtime) of the open file
        self.tags = {}  # "dict_id:name" -> (category, notes)
        self.presets = {}
        self.styles = {}  # title -> styles.css
        self.error = None

    def database(self):
        with self.lock:
            path = self.config.path_of("dictionary")
            try:
                stamp = (path, path.stat().st_mtime)
            except OSError:
                self.close()
                return None
            if self.db is not None and stamp == self.opened:
                return self.db
            self.close()
            self.opened = stamp
            try:
                self.db = self._open(path)
            except Exception as e:
                self.error = str(e)
                return None
            d = self.db
            self.tags = {f"{r[0]}:{r[1]}": (r[2], r[3]) for r in d.execute("SELECT dict_id, name, category, notes FROM tags")}
            self.presets = dict(d.execute("SELECT id, preset FROM dictionaries"))
            version = d.execute("PRAGMA user_version").fetchone()[0]
            self.styles = {} if version < 3 else {
                title: css for title, css in d.execute("SELECT title, styles FROM dictionaries") if css and css.strip()
            }
            return d

    @staticmethod
    def _open(path: Path):
        # check_same_thread off: lookups come from the page's request thread, guarded by self.lock.
        db = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True, check_same_thread=False)
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if not MIN_SCHEMA_VERSION <= version <= SCHEMA_VERSION:
            db.close()
            raise ValueError("it was built by a different version of tools/build_dict.py, rebuild it")
        return db

    def close(self):
        with self.lock:
            if self.db is not None:
                self.db.close()
            self.db = None
            self.opened = None
            self.error = None
            self.styles = {}

    def dictionaries(self, db=None) -> list[Info] | None:
        db = db or self.database()
        if db is None:
            return None
        order = self.config["dictionary_order"]
        disabled = set(self.config["disabled_dictionaries"])
        rows = db.execute("SELECT id, title, priority, terms, meta, kanji FROM dictionaries").fetchall()
        # Titles the user ordered come first, the rest keep the order they were given to build_dict.py.
        rows.sort(key=lambda r: (order.index(r[1]) if r[1] in order else len(order), r[2]))
        return [Info(r[0], r[1], r[3], r[4], r[5], r[1] not in disabled) for r in rows]

    def find(self, text: str, offset: int) -> Result | None:
        """Entries for words starting at [offset], longest match first, like Yomitan's scan."""
        with self.lock:
            db = self.database()
            if db is None:
                return None
            return self._find(db, text, offset)

    def _find(self, db, text: str, offset: int) -> Result:
        dictionaries = [d for d in self.dictionaries(db) if d.enabled]
        if not dictionaries:
            return Result(0, message="All dictionaries are turned off in the settings.")
        # Only the run of Japanese from the offset is looked up: English, punctuation and brackets end the scan, and
        # a lookup starting on one finds nothing without asking the database.
        limit = offset
        while limit < len(text) and limit - offset < MAX_LENGTH and is_japanese(text[limit]):
            limit += 1
        if limit == offset:
            return Result(0)
        by_id = {d.id: d for d in dictionaries}
        rank = {d.id: i for i, d in enumerate(dictionaries)}
        seen = set()
        matches_ = []  # (rowid, info, rank, term, reading, tags, score, length, reasons)
        for end in range(limit, offset, -1):
            candidates = {}
            unique = set()
            for variant in variants(text[offset:end]):
                for c in self.deinflector.deinflect(variant):
                    # Breadth-first, so the first of each (text, conditions) has the shortest chain.
                    if c.text and (c.text, c.conditions) not in unique:
                        unique.add((c.text, c.conditions))
                        candidates.setdefault(c.text, []).append(c)
            # Glossaries are left out here: short kana match hundreds of entries, and only the shown ones are read.
            for row in self._query(db, "SELECT rowid, dict_id, term, reading, def_tags, rules, score, term_tags "
                                       "FROM terms WHERE term IN (%s) OR reading IN (%s)", list(candidates)):
                rowid, dict_id, term, reading, def_tags, rules, score, term_tags = row
                info = by_id.get(dict_id)
                if info is None:
                    continue
                pos = self.deinflector.conditions_of(rules)
                candidate = next((c for c in candidates.get(term, []) + candidates.get(reading, [])
                                  if matches(c.conditions, pos)), None)
                if candidate is None or rowid in seen:
                    continue
                seen.add(rowid)
                tags = list(dict.fromkeys(t for t in def_tags.split(" ") + term_tags.split(" ") if t))
                matches_.append((rowid, info, rank[info.id], term, reading, tags, score, end - offset,
                                 list(candidate.reasons)))

        # One entry per term and reading, ordered like Yomitan: longest match, fewest inflections undone, dictionary
        # order, then the dictionary's own score.
        grouped = {}
        for m in matches_:
            grouped.setdefault((m[3], m[4]), []).append(m)
        groups = [sorted(g, key=lambda m: (m[2], -m[6])) for g in grouped.values()]
        groups.sort(key=lambda g: (-max(m[7] for m in g), min(len(m[8]) for m in g), min(m[2] for m in g),
                                   -max(m[6] for m in g)))
        groups = groups[:MAX_ENTRIES]
        meta = self._meta(db, list(dict.fromkeys(g[0][3] for g in groups)), by_id, rank)
        glossaries = self._glossaries(db, [m[0] for g in groups for m in g])
        entries = []
        for group in groups:
            best = min(group, key=lambda m: (-m[7], len(m[8])))
            forms = [f for f in meta.get(best[3], []) if f["reading"] is None or f["reading"] == best[4]]
            definitions = []
            for m in group:
                tags = [Tag(name, *self.tags.get(f"{m[1].id}:{name}", ("", ""))) for name in m[5]]
                definitions.append(Definition(m[1].title, tags, json.loads(glossaries[m[0]])))
            entries.append(Entry(
                best[3], best[4], best[8], definitions,
                [Frequency(f["dictionary"], f["frequency"]) for f in forms if f["frequency"] is not None],
                [Pitch(f["dictionary"], f["pitches"]) for f in forms if f["pitches"] is not None],
                best[7],
            ))
        length = max((m[7] for m in matches_), default=0)
        return Result(length, entries, self._kanji(db, text, offset, by_id, rank))

    def _glossaries(self, db, ids: list) -> dict:
        """Decompressed glossary JSON by rowid (raw deflate against the dictionary's preset, as build_dict.py writes)."""
        found = {}
        for rowid, dict_id, data in self._query(db, "SELECT rowid, dict_id, glossary FROM terms WHERE rowid IN (%s)",
                                                ids):
            preset = self.presets.get(dict_id)
            inflater = zlib.decompressobj(-15, zdict=preset) if preset else zlib.decompressobj(-15)
            found[rowid] = (inflater.decompress(data) + inflater.flush()).decode("utf-8")
        return found

    def _meta(self, db, terms, by_id, rank) -> dict:
        found = []
        for dict_id, term, mode, data in self._query(
                db, "SELECT dict_id, term, mode, data FROM term_meta WHERE term IN (%s)", terms):
            info = by_id.get(dict_id)
            if info is None:
                continue
            data = json.loads(data)
            reading = data.get("reading") or None if isinstance(data, dict) else None
            item = {"dictionary": info.title, "rank": rank[info.id], "reading": reading, "frequency": None,
                    "pitches": None}
            if mode == "freq":
                value = data["frequency"] if isinstance(data, dict) and "frequency" in data else data
                item["frequency"] = _frequency(value)
                if item["frequency"] is None:
                    continue
            elif mode == "pitch":
                pitches = data.get("pitches") if isinstance(data, dict) else None
                if not isinstance(pitches, list):
                    continue
                item["pitches"] = [str(p["position"]) for p in pitches if isinstance(p, dict) and "position" in p]
            else:
                continue
            found.append((term, item))
        found.sort(key=lambda f: f[1]["rank"])
        out = {}
        for term, item in found:
            out.setdefault(term, []).append(item)
        return out

    def _kanji(self, db, text, offset, by_id, rank) -> list:
        if offset >= len(text) or not is_han(text[offset]):
            return []
        found = []
        for dict_id, character, onyomi, kunyomi, meanings in db.execute(
                "SELECT dict_id, character, onyomi, kunyomi, meanings FROM kanji WHERE character = ?", (text[offset],)):
            info = by_id.get(dict_id)
            if info is not None:
                found.append((rank[info.id], Kanji(info.title, character, onyomi, kunyomi,
                                                   [str(m) for m in json.loads(meanings)])))
        return [k for _, k in sorted(found, key=lambda f: f[0])]

    @staticmethod
    def _query(db, sql: str, values: list):
        """Runs [sql] with each %s replaced by placeholders for [values], in chunks under the parameter limit."""
        uses = sql.count("%s")
        step = MAX_PARAMS // uses
        for i in range(0, len(values), step):
            chunk = values[i:i + step]
            marks = ",".join("?" * len(chunk))
            yield from db.execute(sql.replace("%s", marks), chunk * uses)
