#!/usr/bin/env python3
"""Mark the common readings of names in a JMnedict Yomitan zip, using a frequency dictionary.

Usage: split_jmnedict.py JMNEDICT.zip FREQ.zip OUT.zip

Made for the JMnedict build whose name entries have no reading and list all their readings, in kana order, as the
definition (山田 → くまだ, やまがた, やまた, やまだ…), with FREQ.zip being BCCWJ. For each reading the frequency
dictionary knows for that spelling:

- the reading moves to the front of the list and is highlighted;
- the most common one becomes the entry's reading, so the list shows under the term's normal headword, with its
  furigana and frequency badges, instead of under a separate headword without a reading.

The readings aren't split into an entry each: one headword per reading would bury a lookup under rare readings and
make every kana lookup match hundreds of names. Entries that already have a reading are copied unchanged. The
highlight is styled by a styles.css added to the zip.
"""
import json
import re
import sys
import zipfile

TERM_BANK = re.compile(r"term_bank_\d+\.json$")
META_BANK = re.compile(r"term_meta_bank_\d+\.json$")
KATAKANA = {c: c - 0x60 for c in range(0x30A1, 0x30F7)}

STYLES = """span[data-sc-content="common-reading"] {
    color: #3fae6a;
    font-weight: bold;
}
"""


def hiragana(s):
    return s.translate(KATAKANA)


def ranks(path):
    """(term, reading in hiragana) -> lowest rank number, i.e. the most common."""
    found = {}
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if not META_BANK.search(name):
                continue
            for term, mode, data in json.loads(zf.read(name)):
                if mode != "freq" or not isinstance(data, dict) or not data.get("reading"):
                    continue
                value = data.get("frequency")
                if isinstance(value, dict):
                    value = value.get("value")
                if not isinstance(value, (int, float)):
                    continue
                key = (term, hiragana(data["reading"]))
                if key not in found or value < found[key]:
                    found[key] = value
    return found


def span(kind, text):
    return {"tag": "span", "data": {"content": kind}, "content": text}


def rewrite(bank, known, stats):
    out = []
    for entry in bank:
        term, reading, glossary = entry[0], entry[1], entry[5]
        if reading or not all(isinstance(g, str) for g in glossary):
            out.append(entry)
            continue
        common = sorted(
            ((known[(term, hiragana(g))], g) for g in glossary if (term, hiragana(g)) in known), key=lambda x: x[0]
        )
        if not common:
            out.append(entry)
            continue
        stats["entries"] += 1
        stats["readings"] += len(common)
        marked = {g for _, g in common}
        entry[1] = common[0][1]
        entry[5] = [{"type": "structured-content", "content": span("common-reading", g)} for _, g in common] + \
            [g for g in glossary if g not in marked]
        out.append(entry)
    return out


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    src, freq, dst = sys.argv[1:]
    known = ranks(freq)
    stats = {"entries": 0, "readings": 0}
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            if info.filename == "styles.css":
                continue
            data = zin.read(info)
            if TERM_BANK.search(info.filename):
                bank = rewrite(json.loads(data), known, stats)
                data = json.dumps(bank, ensure_ascii=False, separators=(",", ":")).encode()
            zout.writestr(info, data, zipfile.ZIP_DEFLATED)
        zout.writestr("styles.css", STYLES, zipfile.ZIP_DEFLATED)
    print(f"wrote {dst} ({stats['readings']:,} common readings marked in {stats['entries']:,} entries)")


if __name__ == "__main__":
    main()
