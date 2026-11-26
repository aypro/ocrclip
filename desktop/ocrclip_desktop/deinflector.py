"""
Undoes Japanese inflections (食べなかった → 食べる) with Yomitan's rules, applied the way Yomitan's LanguageTransformer
applies them; a port of Deinflector.kt. The rules are the Android app's assets/japanese-transforms.json (Yomitan's,
Copyright (C) 2024-2026 Yomitan Authors, GPL-3.0-or-later), read in place so both apps share one copy.
"""

import json
import os
from dataclasses import dataclass
from pathlib import Path

from .config import REPO

TRANSFORMS = Path(os.environ.get("OCRCLIP_TRANSFORMS", REPO / "app/src/main/assets/japanese-transforms.json"))


@dataclass(frozen=True)
class Candidate:
    """[conditions]: what the entry's part of speech must match (0: anything); [reasons]: outermost first."""
    text: str
    conditions: int
    reasons: tuple


def matches(current: int, next_: int) -> bool:
    """Yomitan's LanguageTransformer.conditionsMatch."""
    return current == 0 or current & next_ != 0


class Deinflector:
    def __init__(self, path: Path = TRANSFORMS):
        root = json.loads(path.read_text())
        self.parts_of_speech = root["partsOfSpeech"]
        # (name, [(whole_word, inflected, deinflected, conditions_in, conditions_out)])
        self.transforms = [
            (t["name"], [(r[0] == "w", r[1], r[2], r[3], r[4]) for r in t["rules"]]) for t in root["transforms"]
        ]

    def deinflect(self, source: str) -> list[Candidate]:
        # (text, conditions, trace of (transform, rule, text), newest first)
        work = [(source, 0, ())]
        i = 0
        while i < len(work):
            text, conditions, trace = work[i]
            i += 1
            for t, (_, rules) in enumerate(self.transforms):
                for r, (whole, inflected, deinflected, cond_in, cond_out) in enumerate(rules):
                    if not matches(conditions, cond_in):
                        continue
                    if text != inflected if whole else not text.endswith(inflected):
                        continue
                    # Yomitan's cycle guard: a rule never applies twice to the same text within one chain.
                    if any(f[0] == t and f[1] == r and f[2] == text for f in trace):
                        continue
                    following = deinflected if whole else text[:len(text) - len(inflected)] + deinflected
                    work.append((following, cond_out, ((t, r, text),) + trace))
        return [Candidate(text, cond, tuple(self.transforms[f[0]][0] for f in trace)) for text, cond, trace in work]

    def conditions_of(self, rules: str) -> int:
        """Condition flags for a dictionary entry's space-separated `rules` (v1, v5, vk, vs, vz, adj-i)."""
        flags = 0
        for rule in rules.split(" "):
            flags |= self.parts_of_speech.get(rule, 0)
        return flags
