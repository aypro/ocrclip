#!/usr/bin/env python3
"""Strip the " [ローマ字](romaji)" tags from the 新和英 Yomitan zip.

Usage: strip_shinwaei.py IN.zip OUT.zip
"""
import json
import re
import sys
import zipfile

ROMAJI = re.compile(r"[ \t]*\[ローマ字\](?:\([^)\n]*\))?")
BANK = re.compile(r"term_bank_\d+\.json$")


def main():
    src, dst = sys.argv[1:3]
    removed = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info)
            if BANK.search(info.filename):
                bank = json.loads(data)
                for entry in bank:
                    for i, g in enumerate(entry[5]):
                        if isinstance(g, str):
                            entry[5][i], n = ROMAJI.subn("", g)
                            removed += n
                data = json.dumps(bank, ensure_ascii=False, separators=(",", ":")).encode()
            zout.writestr(info, data, zipfile.ZIP_DEFLATED)
    print(f"wrote {dst} ({removed:,} tags removed)")


if __name__ == "__main__":
    main()
