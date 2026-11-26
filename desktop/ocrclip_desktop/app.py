"""
The running app: a new screenshot in the watched folder → crop → OCR (Lens, or meikiocr on the PC when Lens can't be
reached) → clipboard → lookup window with the translation on top. OcrService.kt's job, triggered by files instead of
a hotkey.
"""

import fnmatch
import hashlib
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.error
from pathlib import Path

from PyQt6.QtCore import QBuffer, QFileSystemWatcher, QIODevice, QObject, QProcess, QRect, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QAction, QActionGroup, QColor, QFont, QIcon, QImage, QPainter, QPixmap
from PyQt6.QtNetwork import QLocalServer
from PyQt6.QtWidgets import QMenu, QSystemTrayIcon

from . import anki_note, deepl, lens, lookup_page
from .anki import Anki, AnkiError, AudioUnavailable
from .config import CACHE_DIR, Config, fill_template
from .dictionary import Definition, LocalDictionary
from .offline_ocr import OfflineOcr
from .region import RegionPicker
from .translate import LocalTranslator
from .window import LookupWindow, Session, utf16_to_index

IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
# Anki is synced this long after the last card added, so a run of cards makes one sync, and never sooner than
# SYNC_INTERVAL after the previous one, to go easy on AnkiWeb.
SYNC_DELAY = 120
SYNC_INTERVAL = 600
SPACE_BETWEEN_CJK = re.compile(r"(?<=[^\x00-\x7F])\s+(?=[^\x00-\x7F])")
SERVER_NAME = f"ocrclip-{os.getuid()}"


def format_lines(lines: list[str], keep_newlines: bool) -> str:
    """OcrService.format: lines joined without separators, stray spaces between CJK characters removed."""
    lines = [SPACE_BETWEEN_CJK.sub("", line).strip() for line in lines]
    return ("\n" if keep_newlines else "").join(line for line in lines if line)


def unreachable(e: BaseException) -> bool:
    """A request that failed for want of a connection, not because the service refused it."""
    if isinstance(e, urllib.error.HTTPError):
        return False
    if isinstance(e, urllib.error.URLError):
        return isinstance(e.reason, OSError)
    return isinstance(e, (ConnectionError, TimeoutError, socket.timeout))


def notify(message: str):
    """Failures with no window to show them in; replaces Notice.kt."""
    try:
        subprocess.Popen(["notify-send", "-a", "OCR Clip", "-t", "3000", "OCR Clip", message])
    except OSError:
        print(f"ocrclip: {message}")


class App(QObject):
    _main = pyqtSignal(object)

    def __init__(self, qapp, offline: bool = False):
        super().__init__()
        self.qapp = qapp
        self.force_offline = offline
        self._main.connect(lambda fn: fn(), Qt.ConnectionType.QueuedConnection)
        self.config = Config()
        self.dictionary = LocalDictionary(self.config)
        self.anki = Anki(self.config)
        self.offline_ocr = OfflineOcr(self.config)
        self.translator = LocalTranslator(self.config)
        self.session = Session()
        self.window = LookupWindow(self)
        self.request = 0
        self.recent = {}  # path -> time handled, against double events for one file
        self.picker = None
        self.last_sync = None  # monotonic time of the last sync started
        self.syncing = False
        self.sync_timer = QTimer(self)
        self.sync_timer.setSingleShot(True)
        self.sync_timer.timeout.connect(self._sync_anki)
        self.quitting = False
        self.restarting_watcher = False

        self.idle = QTimer(self)
        self.idle.timeout.connect(self._stop_idle)
        self.idle.start(60_000)

        self.watcher = None
        self._watch()
        self.tray = self._tray()
        self.config.listeners.append(self._config_changed)
        # The file, for edits in place, and its folder, since a save replaces the file and drops the file's watch.
        self.config_watcher = QFileSystemWatcher([str(self.config.path.parent), str(self.config.path)], self)
        self.config_watcher.directoryChanged.connect(self._config_file_changed)
        self.config_watcher.fileChanged.connect(self._config_file_changed)

        self.server = QLocalServer(self)
        QLocalServer.removeServer(SERVER_NAME)
        self.server.listen(SERVER_NAME)
        self.server.newConnection.connect(self._command_connection)
        qapp.aboutToQuit.connect(self.close)

    def on_main(self, fn):
        self._main.emit(fn)

    # Watching the folder

    def _watch(self):
        directory = self.config.path_of("watch_dir")
        if not directory.is_dir():
            print(f"ocrclip: {directory} doesn't exist; checking again in 30 s")
            QTimer.singleShot(30_000, self._watch)
            return
        self.watcher = QProcess(self)
        self.watcher.setProgram("inotifywait")
        # close_write: the file is complete; moved_to: tools that write a temporary file and rename it.
        self.watcher.setArguments(["-m", "-q", "-e", "close_write", "-e", "moved_to", "--format", "%f",
                                   str(directory)])
        self.watcher.readyReadStandardOutput.connect(self._watched)
        self.watcher.finished.connect(self._watcher_ended)
        # Killing it on quit or for a new watch_dir reports a crash; only an unexpected one is worth saying.
        self.watcher.errorOccurred.connect(
            lambda error: self.quitting or self.restarting_watcher or print(f"ocrclip: inotifywait: {error.name}"))
        self.watcher.start()

    def _watcher_ended(self):
        self.restarting_watcher = False
        if not self.quitting:
            QTimer.singleShot(2000, self._watch)

    def _watched(self):
        directory = self.config.path_of("watch_dir")
        while self.watcher.canReadLine():
            name = bytes(self.watcher.readLine()).decode("utf-8", "replace").rstrip("\n")
            if name:
                self.screenshot_added(directory / name)

    def screenshot_added(self, path: Path):
        if path.suffix.lower() not in IMAGE_EXTENSIONS or not self.config["enabled"]:
            return
        if any(fnmatch.fnmatch(path.name, g) for g in self.config["skip"]):
            return
        now = time.monotonic()
        if now - self.recent.get(path, -10) < 2:
            return
        self.recent = {p: t for p, t in self.recent.items() if now - t < 10}
        self.recent[path] = now
        self.capture(path)

    # Capture

    def capture(self, path: Path):
        """Reads [path] and shows the lookup. A newer capture makes this one's results go unused."""
        self.request += 1
        request = self.request
        self.config["last_screenshot"] = str(path)
        threading.Thread(target=self._recognize, args=(path, request), daemon=True).start()

    def _recognize(self, path: Path, request: int):
        image = QImage(str(path))
        if image.isNull():
            return self.on_main(lambda: notify(f"Can't read {path.name}"))
        region = self.config["region"]
        if region and not any(fnmatch.fnmatch(path.name, g) for g in self.config["crop_skip"]):
            l, t, r, b = region
            image = image.copy(QRect(int(l * image.width()), int(t * image.height()),
                                     max(1, int((r - l) * image.width())), max(1, int((b - t) * image.height()))))
        lens_skipped = False
        try:
            if self.config["engine"] == "lens" and not self.force_offline:
                try:
                    lines = lens.recognize(image)
                except Exception as e:
                    if not unreachable(e):
                        return self.on_main(lambda: notify(f"Google Lens failed: {e}"))
                    lens_skipped = True
                    lines = self._offline_lines(image)
            else:
                lens_skipped = self.config["engine"] == "lens"
                lines = self._offline_lines(image)
        except Exception as e:
            return self.on_main(lambda: notify(f"OCR failed: {e}"))
        text = format_lines(lines, self.config["keep_newlines"])
        self.on_main(lambda: self._deliver(text, path, request, lens_skipped))

    def _offline_lines(self, image: QImage) -> list[str]:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        crop = CACHE_DIR / "capture.png"
        image.save(str(crop), "PNG")
        return self.offline_ocr.recognize(crop)

    def _deliver(self, text: str, path: Path, request: int, lens_skipped: bool):
        if request != self.request:
            return
        if not text:
            notify("No text found")
            return
        if self.config["copy_to_clipboard"]:
            try:
                subprocess.run(["wl-copy"], input=text, text=True, timeout=5)
            except (OSError, subprocess.SubprocessError) as e:
                print(f"ocrclip: wl-copy: {e}")
        self.config["last_result"] = text
        self.session = Session(text, path)
        self.window.show_page(lookup_page.page(text, self.config["lookup_font"]))
        self._translate(text, request, lens_skipped)

    # Translation, as OcrService.translate: DeepL with the offline model as its fallback, or the offline model alone

    def _current(self, request: int) -> bool:
        return request == self.request and self.window.isVisible()

    def _translate(self, text: str, request: int, lens_skipped: bool):
        w = self.window
        key = self.config["deepl_key"]
        use_deepl = self.config["translate_engine"] == "deepl" and bool(key)
        offline = self.translator.available
        ocr = "On-device OCR" if lens_skipped else None
        no_connection = lens_skipped or self.force_offline
        if not use_deepl and not offline:
            notes = [n for n in (ocr, "No offline translation model" if not use_deepl else None) if n]
            w.set_translator(fill_template(self.config["translate_url"], text))
            if notes:
                w.set_note(" · ".join(notes))
            return
        if not use_deepl:
            return self._translate_offline(text, request, [n for n in (ocr,) if n])
        # Without a connection, straight to the offline model rather than waiting for DeepL to fail.
        if offline and no_connection:
            return self._translate_offline(text, request, [n for n in ("No connection", ocr) if n])
        w.set_note("Translating…")
        target = self.config["deepl_target"]

        def run():
            try:
                result = deepl.translate(text, key, target)
                failure = None
            except Exception as e:
                result, failure = None, e

            def show():
                if not self._current(request):
                    return
                if failure is None:
                    w.set_translation(result)
                    w.set_note(ocr or "")
                    return
                why = "No connection" if unreachable(failure) else f"DeepL failed: {failure}"
                if offline:
                    return self._translate_offline(text, request, [n for n in (why, ocr) if n])
                w.set_note(why)
                w.set_translator(fill_template(self.config["translate_url"], text))
            self.on_main(show)
        threading.Thread(target=run, daemon=True).start()

    def _translate_offline(self, text: str, request: int, notes: list):
        w = self.window
        note = " · ".join(notes + ["Offline translation"])
        w.set_note(note)

        def loading():
            self.on_main(lambda: self._current(request) and w.set_note(note + " · loading the model…"))

        def progress(partial: str) -> bool:
            def show():
                if self._current(request):
                    w.set_note(note)
                    w.set_translation(partial)
            self.on_main(show)
            return request == self.request

        def run():
            try:
                self.translator.translate(text, loading, progress)
            except Exception as e:
                def failed():
                    if self._current(request):
                        w.set_note(f"Offline translation failed: {e}")
                        w.set_translator(fill_template(self.config["translate_url"], text))
                self.on_main(failed)
        threading.Thread(target=run, daemon=True).start()

    # Anki, from the lookup page's + buttons (on the bridge's threads)

    def anki_existing(self, terms) -> tuple[set, str | None]:
        """Which of [terms] already have a note, and why that couldn't be found out, if it couldn't."""
        model = self.config["anki_model"]
        if not model:
            return set(), None
        try:
            return self.anki.existing(model, anki_note.term_field(self.anki.fields(model)), terms), None
        except AnkiError as e:
            return set(), f"{e}: words already in Anki aren't marked"
        except Exception as e:
            return set(), f"Anki: {e}"

    def add_to_anki(self, session: Session, entry: int, definition: int, sense: int, offset: int) -> dict:
        entries = session.shown.entries if session.shown else []
        e = entries[entry] if 0 <= entry < len(entries) else None
        d = e.definitions[definition] if e and 0 <= definition < len(e.definitions) else None
        if d is not None and sense >= 0:
            glossary = lookup_page.only_sense(d.glossary, sense)
            d = Definition(d.dictionary, d.tags, glossary) if glossary is not None else None
        model = self.config["anki_model"]
        if not model:
            return {"error": "Set \"anki_model\" in ~/.config/ocrclip/settings.json"}
        if e is None or d is None:
            return {"error": "That entry is gone, look it up again"}
        notice = None
        try:
            names = self.anki.fields(model)
            picture = None
            if anki_note.wants_picture(names) and session.screenshot and Path(session.screenshot).exists():
                jpeg = _jpeg(Path(session.screenshot))
                picture = self.anki.store_media(f"ocrclip_{hashlib.sha1(jpeg).hexdigest()[:16]}.jpg", jpeg)
            audio = None
            if anki_note.wants_audio(names):
                # A note without its recording is still worth having, so nothing here stops it.
                try:
                    clip = self.anki.audio(e.term, e.reading)
                except AudioUnavailable as x:
                    clip = None
                    notice = f"Added without audio: {x}"
                if clip is None:
                    notice = notice or "No audio for this word"
                else:
                    data, extension = clip
                    stem = e.term if not e.reading or e.reading == e.term else f"{e.term}_{e.reading}"
                    audio = self.anki.store_media(f"ocrclip_{stem}.{extension}", data)
            word_offset = -1 if offset < 0 else utf16_to_index(session.text, offset)
            fields = anki_note.fields(names, anki_note.Source(e, d, session.text, word_offset, picture, audio,
                                                              self.dictionary.styles))
            self.anki.add_note(model, fields, [t for t in self.config["anki_tags"].split(" ") if t])
        except AnkiError as x:
            return {"error": str(x)}
        except Exception as x:
            return {"error": f"Anki: {x}"}
        self.on_main(self._schedule_sync)
        return {"notice": notice} if notice else {}

    def _schedule_sync(self):
        """(Re)starts the countdown to a sync, so cards reach AnkiWeb (and AnkiDroid) without waiting for Anki to close."""
        if not self.config["anki_sync"]:
            return
        delay = SYNC_DELAY
        if self.last_sync is not None:
            delay = max(delay, self.last_sync + SYNC_INTERVAL - time.monotonic())
        self.sync_timer.start(int(delay * 1000))

    def _sync_anki(self):
        if self.syncing:  # still going; the cards added since get the next one
            self.sync_timer.start(SYNC_DELAY * 1000)
            return
        self.syncing = True
        self.last_sync = time.monotonic()

        def run():
            try:
                self.anki.sync()
                print("ocrclip: Anki synced")
                failure = None
            except AnkiError as e:
                failure = str(e)
            except Exception as e:
                failure = f"Anki: {e}"

            def done():
                self.syncing = False
                if failure is None:
                    return
                print(f"ocrclip: Anki sync failed: {failure}")
                # Anki also syncs when it next opens or closes, so the cards aren't lost, only later.
                if "didn't answer in time" in failure:
                    notify("Anki's sync didn't finish. It may be asking whether to upload or download: "
                           "open Anki (Super+S)")
                else:
                    notify(f"Anki sync failed: {failure}. Anki syncs again when it opens or closes")
            self.on_main(done)
        threading.Thread(target=run, daemon=True).start()

    # Tray, commands, settings

    def _tray(self):
        tray = QSystemTrayIcon(_icon(), self)
        tray.setToolTip("OCR Clip")
        menu = QMenu()
        self.pause_action = QAction("Pause", menu, checkable=True)
        self.pause_action.setChecked(not self.config["enabled"])
        self.pause_action.toggled.connect(lambda paused: self._set("enabled", not paused))
        menu.addAction(self.pause_action)
        menu.addAction("Show last lookup", self.show_window)
        menu.addAction("Pick region…", self.pick_region)
        menu.addSeparator()
        self.choices = {}
        for key, title, options in (
                ("engine", "OCR", (("lens", "Google Lens"), ("local", "On-device (meikiocr)"))),
                ("translate_engine", "Translate with", (("deepl", "DeepL"), ("offline", "Offline model")))):
            sub = menu.addMenu(title)
            group = QActionGroup(sub)
            for value, label in options:
                action = QAction(label, sub, checkable=True)
                action.setChecked(self.config[key] == value)
                action.triggered.connect(lambda _, k=key, v=value: self._set(k, v))
                group.addAction(action)
                sub.addAction(action)
                self.choices[(key, value)] = action
        menu.addAction("Open settings file", lambda: subprocess.Popen(["xdg-open", str(self.config.path)]))
        menu.addSeparator()
        menu.addAction("Quit", self.qapp.quit)
        tray.setContextMenu(menu)
        self.tray_menu = menu
        tray.activated.connect(lambda reason: reason == QSystemTrayIcon.ActivationReason.Trigger and
                               self.pause_action.toggle())
        tray.show()
        return tray

    def _set(self, key, value):
        self.config[key] = value

    def _config_file_changed(self, _):
        if self.config.path.exists() and str(self.config.path) not in self.config_watcher.files():
            self.config_watcher.addPath(str(self.config.path))
        self.config.reload_if_changed()

    def _config_changed(self, key):
        if key == "enabled":
            self.pause_action.setChecked(not self.config["enabled"])
            self.tray.setToolTip("OCR Clip" + ("" if self.config["enabled"] else " (paused)"))
            self.tray.setIcon(_icon(paused=not self.config["enabled"]))
        elif key == "watch_dir" and self.watcher is not None:
            self.restarting_watcher = True
            self.watcher.kill()  # restarted on the new folder by _watcher_ended
        for (k, v), action in self.choices.items():
            if k == key:
                action.setChecked(self.config[k] == v)

    def show_window(self):
        if self.session.text:
            self.window.show()
            self.window.raise_()
            self.window.activateWindow()

    def pick_region(self):
        path = Path(self.config["last_screenshot"])
        image = QImage(str(path)) if self.config["last_screenshot"] else QImage()
        if image.isNull():
            notify("Take a screenshot in the game first, then pick the region on it")
            return
        self.picker = RegionPicker(image, self.config["region"])
        self.picker.picked.connect(lambda region: self._set("region", region))
        self.picker.show()

    def _command_connection(self):
        conn = self.server.nextPendingConnection()

        def read():
            if not conn.canReadLine():
                return
            try:
                command = json.loads(bytes(conn.readLine()).decode())
                self.command(command["command"], command.get("args", []))
                conn.write(b"ok\n")
            except Exception as e:
                conn.write(f"error: {e}\n".encode())
            conn.flush()
            conn.disconnectFromServer()
        conn.readyRead.connect(read)

    def command(self, name: str, args: list):
        if name == "once":
            for a in args:
                self.capture(Path(a).expanduser().resolve())
        elif name == "toggle":
            self._set("enabled", not self.config["enabled"])
        elif name in ("pause", "resume"):
            self._set("enabled", name == "resume")
        elif name == "hide":
            self.window.hide()
        elif name == "show":
            self.show_window()
        elif name == "region":
            self.pick_region()
        elif name == "quit":
            self.qapp.quit()
        elif name != "run":
            raise ValueError(f"unknown command {name}")

    def _stop_idle(self):
        threading.Thread(target=lambda: (self.offline_ocr.stop_if_idle(), self.translator.stop_if_idle()),
                         daemon=True).start()

    def close(self):
        self.quitting = True
        if self.watcher is not None:
            self.watcher.kill()
            self.watcher.waitForFinished(1000)
        self.offline_ocr.close()
        self.translator.close()
        self.server.close()


def _jpeg(path: Path) -> bytes:
    buffer = QBuffer()
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    QImage(str(path)).convertToFormat(QImage.Format.Format_RGB888).save(buffer, "JPG", 90)
    return bytes(buffer.data())


def _icon(paused: bool = False) -> QIcon:
    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.GlobalColor.transparent)
    p = QPainter(pixmap)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setBrush(QColor("#555" if paused else "#2d4f6c"))
    p.setPen(Qt.PenStyle.NoPen)
    p.drawRoundedRect(4, 4, 56, 56, 12, 12)
    p.setPen(QColor("#fff"))
    p.setFont(QFont("sans-serif", 30))
    p.drawText(pixmap.rect(), Qt.AlignmentFlag.AlignCenter, "字")
    p.end()
    return QIcon(pixmap)
