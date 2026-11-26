"""Settings in ~/.config/ocrclip/settings.json, with the same keys as the Android app's Prefs where they still apply."""

import json
import os
import subprocess
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "ocrclip"
DATA_DIR = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "ocrclip"
CACHE_DIR = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "ocrclip"
FILE = CONFIG_DIR / "settings.json"

# The lookup page's CSS and script and the deinflection rules, shared with the Android app: inside the package in a
# release, the repo's shared/ in a checkout.
_PACKAGED = Path(__file__).resolve().parent / "shared"
SHARED = Path(os.environ.get("OCRCLIP_SHARED") or
              (_PACKAGED if _PACKAGED.is_dir() else Path(__file__).resolve().parents[2] / "shared"))


def _pictures() -> Path:
    try:
        out = subprocess.run(["xdg-user-dir", "PICTURES"], capture_output=True, text=True, timeout=2).stdout.strip()
        if out:
            return Path(out)
    except (OSError, subprocess.SubprocessError):
        pass
    return Path.home() / "Pictures"


DEFAULTS = {
    "enabled": True,
    "watch_dir": str(_pictures() / "Screenshots"),
    "engine": "lens",  # lens / local
    "keep_newlines": False,
    "copy_to_clipboard": True,
    "dictionary": str(DATA_DIR / "dict.db"),
    "dictionary_order": [],
    "disabled_dictionaries": [],
    "translate_engine": "deepl",  # deepl / offline
    "translate_model": str(DATA_DIR / "translate.gguf"),
    "llama_server": "llama-server",
    "deepl_key": "",
    "deepl_target": "EN-US",
    "translate_url": "https://www.deepl.com/translator#ja/en/{text}",
    "offline_ocr_python": str(Path.home() / ".local/share/uv/tools/owocr/bin/python"),
    "lookup_font": "",
    "anki_model": "",
    "anki_deck": "",
    "anki_tags": "ocrclip",
    "anki_sync": True,  # sync Anki with AnkiWeb a little after cards are added
    "anki_connect": "http://127.0.0.1:8765",
    "local_audio": "http://127.0.0.1:5050",
    "region": None,  # [left, top, right, bottom] as fractions of the image
    "crop_skip": ["screenshot-*"],  # omasnap's shots are already the region the user picked
    "skip": [],
    "last_result": "",
    "last_screenshot": "",
}


class Config:
    def __init__(self, path: Path = FILE):
        self.path = path
        self.values = dict(DEFAULTS)
        self.listeners = []
        self.stamp = None  # mtime of the file as last read or written
        self.load()

    def _mtime(self):
        try:
            return self.path.stat().st_mtime_ns
        except OSError:
            return None

    def load(self):
        try:
            self.values = {**DEFAULTS, **json.loads(self.path.read_text())}
            self.stamp = self._mtime()
        except FileNotFoundError:
            self.save()
        except (OSError, ValueError) as e:
            print(f"ocrclip: can't read {self.path}: {e}; keeping the settings in use")

    def reload_if_changed(self):
        """Picks up edits made to the file by hand, and tells the listeners which keys changed. Main thread only."""
        if self._mtime() == self.stamp:
            return
        before = self.values
        self.load()
        for key in [k for k in self.values if self.values[k] != before.get(k)]:
            for listener in self.listeners:
                listener(key)

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.values, indent=2, ensure_ascii=False) + "\n")
        os.replace(tmp, self.path)
        self.stamp = self._mtime()

    def __getitem__(self, key):
        return self.values[key]

    def __setitem__(self, key, value):
        if self.values.get(key) == value:
            return
        # A hand edit not picked up yet would otherwise be written over.
        if self._mtime() != self.stamp:
            self.load()
        self.values[key] = value
        self.save()
        for listener in self.listeners:
            listener(key)

    def path_of(self, key) -> Path:
        return Path(os.path.expanduser(self.values[key]))


def fill_template(template: str, text: str) -> str:
    from urllib.parse import quote
    return template.replace("{text}", quote(text, safe=""))
