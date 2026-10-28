#!/usr/bin/env python3
"""Exports Yomitan's Japanese deinflection rules as JSON for the app's local dictionary lookup.

    git clone --depth 1 https://github.com/yomidevs/yomitan.git
    python tools/export_yomitan_transforms.py yomitan app/src/main/assets/japanese-transforms.json

The rules are Yomitan's (GPL-3.0-or-later, Copyright (C) 2024-2026 Yomitan Authors), only converted to data so they
can be refreshed from upstream instead of maintained by hand. japanese-transforms.js is parsed as text rather than run,
and anything this script doesn't recognise is an error, so an upstream change can't silently drop rules.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

STR = r"'((?:[^'\\]|\\.)*)'"
LIST = r"\[([^\]]*)\]"
CALL = re.compile(
    rf"suffixInflection\({STR}, {STR}, {LIST}, {LIST}\)"
    rf"|wholeWordInflection\({STR}, {STR}, {LIST}, {LIST}\)"
    rf"|\.\.\.irregularVerbSuffixInflections\({STR}, {LIST}, {LIST}\)"
    rf"|\.\.\.specialHonorificMasuInflections\({LIST}, {LIST}\)"
)
# The two rule-generating helpers are re-implemented below; if their bodies change upstream, so must this script.
HELPER_BODIES = [
    "suffixInflection(`${verb[0]}っ${suffix}`, verb, conditionsIn, conditionsOut)",
    "suffixInflection(`${verb}${suffix}`, verb, conditionsIn, conditionsOut)",
    "suffixInflection(`${teRoot}${suffix}`, verb, conditionsIn, conditionsOut)",
    "wholeWordInflection(`${verb.slice(0, -1)}います`, verb, conditionsIn, conditionsOut)",
]


def fail(message):
    sys.exit(f"export_yomitan_transforms: {message}")


def unescape(s):
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def strings(s):
    return [unescape(x) for x in re.findall(STR, s)]


def const_array(text, name):
    m = re.search(rf"^const {name} = \[(.*?)\];", text, re.M | re.S)
    if not m:
        fail(f"const {name} not found")
    return m.group(1)


def split_entries(block, indent):
    """Splits an object literal body into (key, body) pairs by its keys at the given indent."""
    keys = list(re.finditer(rf"^{' ' * indent}'((?:[^'\\]|\\.)*)': \{{", block, re.M))
    return [(unescape(k.group(1)), block[k.end():keys[i + 1].start() if i + 1 < len(keys) else len(block)])
            for i, k in enumerate(keys)]


def condition_flags(conditions):
    """Same assignment as Yomitan's LanguageTransformer: a bit per leaf, parents are the union of sub-conditions."""
    flags, bit, pending = {}, 0, list(conditions.items())
    while pending:
        later = []
        for name, (_, subs) in pending:
            if subs is None:
                if bit >= 31:
                    fail("too many conditions for a 32-bit Int")
                flags[name] = 1 << bit
                bit += 1
            elif all(s in flags for s in subs):
                flags[name] = 0
                for s in subs:
                    flags[name] |= flags[s]
            else:
                later.append((name, (_, subs)))
        if len(later) == len(pending):
            fail("cycle in subConditions")
        pending = later
    return flags


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root, out = Path(sys.argv[1]), Path(sys.argv[2])
    text = (root / "ext/js/language/ja/japanese-transforms.js").read_text(encoding="utf-8")
    try:
        commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                                capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit = "unknown"

    for body in HELPER_BODIES:
        if body not in text:
            fail(f"helper changed upstream, update this script: {body}")
    iku = strings(const_array(text, "ikuVerbs"))
    godan_u = strings(const_array(text, "godanUSpecialVerbs"))
    honorific = strings(const_array(text, "specialHonorificMasuVerbs"))
    fu_te = re.findall(rf"\[\s*{STR},\s*{STR}\s*\]", const_array(text, "fuVerbTeConjugations"))
    if not (iku and godan_u and honorific and fu_te):
        fail("empty helper verb list")

    m = re.search(r"^const conditions = \{\n(.*?)^\};", text, re.M | re.S)
    if not m:
        fail("conditions not found")
    conditions = {}
    for name, body in split_entries(m.group(1), 4):
        dictionary_form = re.search(r"^        isDictionaryForm: (true|false),", body, re.M)
        if not dictionary_form:
            fail(f"condition {name} has no isDictionaryForm")
        subs = re.search(r"^        subConditions: (\[[^\]]*\])", body, re.M)
        conditions[name] = (dictionary_form.group(1) == "true", strings(subs.group(1)) if subs else None)
    flags = condition_flags(conditions)

    def to_flags(types):
        result = 0
        for t in strings(types):
            if t not in flags:
                fail(f"unknown condition {t}")
            result |= flags[t]
        return result

    m = re.search(r"^    transforms: \{\n(.*)", text, re.M | re.S)
    if not m:
        fail("transforms not found")
    transforms = []
    for transform_id, body in split_entries(m.group(1), 8):
        name = re.search(rf"^            name: {STR},", body, re.M)
        rules_block = re.search(r"^            rules: \[\n(.*?)^            \],", body, re.M | re.S)
        if not name or not rules_block:
            fail(f"transform {transform_id} has no name or rules")
        rules, src, pos = [], rules_block.group(1), 0
        while True:
            while pos < len(src) and src[pos] in " \n\r\t,":
                pos += 1
            if pos == len(src):
                break
            c = CALL.match(src, pos)
            if not c:
                fail(f"unrecognised rule in {transform_id}: {src[pos:pos + 80]!r}")
            g = c.groups()
            if g[0] is not None:
                rules.append(["s", unescape(g[0]), unescape(g[1]), to_flags(g[2]), to_flags(g[3])])
            elif g[4] is not None:
                rules.append(["w", unescape(g[4]), unescape(g[5]), to_flags(g[6]), to_flags(g[7])])
            elif g[8] is not None:
                suffix, ci, co = unescape(g[8]), to_flags(g[9]), to_flags(g[10])
                rules += [["s", f"{v[0]}っ{suffix}", v, ci, co] for v in iku]
                rules += [["s", f"{v}{suffix}", v, ci, co] for v in godan_u]
                rules += [["s", f"{unescape(root)}{suffix}", unescape(v), ci, co] for v, root in fu_te]
            else:
                ci, co = to_flags(g[11]), to_flags(g[12])
                rules += [["w", f"{v[:-1]}います", v, ci, co] for v in honorific]
            pos = c.end()
        transforms.append({"id": transform_id, "name": unescape(name.group(1)), "rules": rules})
    if not transforms:
        fail("no transforms found")

    parts_of_speech = {n: flags[n] for n, (dictionary_form, _) in conditions.items() if dictionary_form}
    # One rule per line keeps upstream updates readable in a diff.
    dump = lambda v: json.dumps(v, ensure_ascii=False)
    source = (f"Yomitan ext/js/language/ja/japanese-transforms.js @ {commit}. "
              "Copyright (C) 2024-2026 Yomitan Authors. GPL-3.0-or-later.")
    lines = [
        "{",
        f'"source": {dump(source)},',
        '"rules": "[type (s = suffix, w = whole word), inflected, deinflected, conditionsIn, conditionsOut]",',
        f'"partsOfSpeech": {dump(parts_of_speech)},',
        '"transforms": [',
    ]
    for i, t in enumerate(transforms):
        lines.append(f'{{"id": {dump(t["id"])}, "name": {dump(t["name"])}, "rules": [')
        lines += [dump(r) + ("," if j < len(t["rules"]) - 1 else "") for j, r in enumerate(t["rules"])]
        lines.append("]}" + ("," if i < len(transforms) - 1 else ""))
    lines += ["]", "}"]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    count = sum(len(t["rules"]) for t in transforms)
    print(f"{len(transforms)} transforms, {count} rules, parts of speech {parts_of_speech} -> {out}")


if __name__ == "__main__":
    main()
