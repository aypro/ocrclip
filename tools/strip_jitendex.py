#!/usr/bin/env python3
"""Strip example sentences and the JMdict | Tatoeba footer from a Jitendex Yomitan zip.

Usage: strip_jitendex.py IN.zip OUT.zip

Removes structured-content nodes whose data-content is "example-sentence" or
"attribution", then drops wrapper divs left empty by that (e.g. "extra-info"
boxes that only held examples). Everything else is copied unchanged.
"""
import json
import re
import sys
import zipfile

DROP = {"example-sentence", "attribution"}
BANK = re.compile(r"term_bank_\d+\.json$")


def clean(node):
    """Return the cleaned node, or None if it should disappear."""
    if isinstance(node, list):
        out = [c for c in (clean(x) for x in node) if c is not None]
        return out or None
    if isinstance(node, dict):
        if (node.get("data") or {}).get("content") in DROP:
            return None
        if "content" in node:
            had = node["content"] is not None
            new = clean(node["content"])
            if had and new is None and node.get("tag") in ("div", "span", "ul", "ol", "li"):
                return None  # wrapper emptied by the removal
            if new is None:
                node.pop("content")
            else:
                node["content"] = new
        return node
    return node


def main():
    src, dst = sys.argv[1:3]
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info)
            if BANK.search(info.filename):
                bank = json.loads(data)
                for entry in bank:
                    entry[5] = [g for g in (clean(g) for g in entry[5]) if g is not None]
                data = json.dumps(bank, ensure_ascii=False, separators=(",", ":")).encode()
            zout.writestr(info, data, zipfile.ZIP_DEFLATED)
    print(f"wrote {dst}")


if __name__ == "__main__":
    main()
