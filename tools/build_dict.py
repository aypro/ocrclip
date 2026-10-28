#!/usr/bin/env python3
"""Builds the dictionary database for OCR Clip's offline lookup from Yomitan dictionary zips.

    python tools/build_dict.py dict.db JMdict_english.zip JPDB_freq.zip Kanjium_pitch.zip ...

Any mix of term, frequency, pitch accent and kanji dictionaries works. The order given is the default display order;
it can be changed (and dictionaries turned off) in the app afterwards. Then copy dict.db to the device and pick it
with "Load dictionary database" in the app.

Parsing the zips takes a while on a PC and much longer on a handheld, which is why this runs here and the app only
reads the finished file. Standard library only.
"""

import json
import os
import re
import sqlite3
import sys
import zipfile

SCHEMA_VERSION = 1  # LocalDictionary.SCHEMA_VERSION

SCHEMA = """
CREATE TABLE dictionaries (
    id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT, priority INTEGER NOT NULL,
    terms INTEGER NOT NULL, meta INTEGER NOT NULL, kanji INTEGER NOT NULL
);
CREATE TABLE terms (
    dict_id INTEGER NOT NULL, term TEXT NOT NULL, reading TEXT NOT NULL, def_tags TEXT NOT NULL,
    rules TEXT NOT NULL, score INTEGER NOT NULL, glossary TEXT NOT NULL, sequence INTEGER, term_tags TEXT NOT NULL
);
CREATE TABLE term_meta (dict_id INTEGER NOT NULL, term TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL);
CREATE TABLE kanji (
    dict_id INTEGER NOT NULL, character TEXT NOT NULL, onyomi TEXT NOT NULL, kunyomi TEXT NOT NULL,
    tags TEXT NOT NULL, meanings TEXT NOT NULL
);
CREATE TABLE tags (dict_id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, notes TEXT NOT NULL);
"""

INDEXES = """
CREATE INDEX terms_term ON terms (term);
CREATE INDEX terms_reading ON terms (reading);
CREATE INDEX term_meta_term ON term_meta (term);
CREATE INDEX kanji_character ON kanji (character);
"""


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def text(value):
    return value if isinstance(value, str) else ""


def banks(zf, kind):
    """Bank files of one kind in numeric order (term_bank_2 before term_bank_10)."""
    pattern = re.compile(rf"(?:.*/)?{kind}_(\d+)\.json$")
    found = [(int(m.group(1)), name) for name in zf.namelist() if (m := pattern.match(name))]
    for _, name in sorted(found):
        with zf.open(name) as f:
            yield json.load(f)


def find_index(zf):
    names = [n for n in zf.namelist() if n == "index.json" or n.endswith("/index.json")]
    if not names:
        return None
    with zf.open(min(names, key=len)) as f:
        return json.load(f)


def add_dictionary(db, path, dict_id):
    with zipfile.ZipFile(path) as zf:
        index = find_index(zf)
        if index is None or "title" not in index:
            print(f"  skipped {path}: no index.json with a title, not a Yomitan dictionary")
            return False
        title = index["title"]
        if db.execute("SELECT 1 FROM dictionaries WHERE title = ?", (title,)).fetchone():
            print(f"  skipped {path}: '{title}' is already in the database")
            return False
        version = index.get("format", index.get("version", 3))

        terms = 0
        for bank in banks(zf, "term_bank"):
            rows = []
            for e in bank:
                if version == 1:
                    # [term, reading, definitionTags, rules, score, ...glossary]
                    glossary, sequence, term_tags = e[5:], None, ""
                else:
                    # [term, reading, definitionTags, rules, score, glossary, sequence, termTags]
                    glossary, sequence, term_tags = e[5], e[6], text(e[7])
                rows.append((dict_id, e[0], e[1] or e[0], text(e[2]), text(e[3]), e[4] or 0,
                             compact(glossary), sequence, term_tags))
            db.executemany("INSERT INTO terms VALUES (?,?,?,?,?,?,?,?,?)", rows)
            terms += len(rows)

        meta = 0
        for bank in banks(zf, "term_meta_bank"):
            # [term, mode, data]; ipa is skipped since the lookup page doesn't show it.
            rows = [(dict_id, e[0], e[1], compact(e[2])) for e in bank if e[1] in ("freq", "pitch")]
            db.executemany("INSERT INTO term_meta VALUES (?,?,?,?)", rows)
            meta += len(rows)

        kanji = 0
        for bank in banks(zf, "kanji_bank"):
            rows = []
            for e in bank:
                # v1: [character, onyomi, kunyomi, tags, ...meanings]; v3: [..., tags, meanings, stats]
                meanings = e[4:] if version == 1 else e[4]
                rows.append((dict_id, e[0], text(e[1]), text(e[2]), text(e[3]), compact(meanings)))
            db.executemany("INSERT INTO kanji VALUES (?,?,?,?,?,?)", rows)
            kanji += len(rows)

        for bank in banks(zf, "tag_bank"):
            # [name, category, order, notes, score]
            db.executemany("INSERT INTO tags VALUES (?,?,?,?)",
                           [(dict_id, e[0], text(e[1]), text(e[3])) for e in bank])

        skipped = [n for n in zf.namelist() if re.search(r"\.(png|jpe?g|gif|svg|webp|bmp|avif)$", n, re.I)]
        db.execute("INSERT INTO dictionaries VALUES (?,?,?,?,?,?,?)",
                   (dict_id, title, str(index.get("revision", "")), dict_id, terms, meta, kanji))
        parts = [f"{n:,} {what}" for n, what in ((terms, "terms"), (meta, "frequency/pitch"), (kanji, "kanji")) if n]
        note = f" ({len(skipped)} images skipped)" if skipped else ""
        print(f"  {title}: {', '.join(parts) or 'nothing usable'}{note}")
        return True


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out, zips = sys.argv[1], sys.argv[2:]
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    db.executescript(SCHEMA)
    dict_id = 1
    for path in zips:
        print(f"Reading {path}")
        try:
            if add_dictionary(db, path, dict_id):
                dict_id += 1
        except (zipfile.BadZipFile, json.JSONDecodeError, IndexError, KeyError, TypeError) as e:
            print(f"  skipped {path}: {e}")
    if dict_id == 1:
        db.close()
        os.remove(tmp)
        sys.exit("No dictionaries were added.")
    print("Indexing...")
    db.executescript(INDEXES)
    db.execute("ANALYZE")
    db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, out)
    print(f"Wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB, {dict_id - 1} dictionaries)")


if __name__ == "__main__":
    main()
