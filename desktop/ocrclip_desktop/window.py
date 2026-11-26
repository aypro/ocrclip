"""
The lookup window: a web view on the lookup page, served from the ocrclip:// scheme, whose /api/ requests are the
page's OCRClip bridge (LocalDictionary's @JavascriptInterface methods on Android).
"""

import json
import subprocess
import threading

from PyQt6.QtCore import QBuffer, QByteArray, QObject, QUrl, QUrlQuery, Qt, pyqtSignal
from PyQt6.QtGui import QKeySequence, QShortcut
from PyQt6.QtWebEngineCore import (QWebEnginePage, QWebEngineProfile, QWebEngineUrlRequestJob, QWebEngineUrlScheme,
                                   QWebEngineUrlSchemeHandler)
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWidgets import QVBoxLayout, QWidget

from . import lookup_page
from .dictionary import Result

SCHEME = b"ocrclip"


def register_scheme():
    """Before the QApplication exists, as Qt requires."""
    scheme = QWebEngineUrlScheme(SCHEME)
    scheme.setSyntax(QWebEngineUrlScheme.Syntax.Host)
    scheme.setFlags(QWebEngineUrlScheme.Flag.SecureScheme | QWebEngineUrlScheme.Flag.LocalScheme
                    | QWebEngineUrlScheme.Flag.LocalAccessAllowed)
    QWebEngineUrlScheme.registerScheme(scheme)


def utf16_to_index(text: str, offset: int) -> int:
    """A UTF-16 offset from the page as a Python string index."""
    units = 0
    for i, ch in enumerate(text):
        if units >= offset:
            return i
        units += 2 if ord(ch) > 0xFFFF else 1
    return len(text)


def utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


class Session:
    """One capture as the lookup page shows it: the text, its screenshot, and the result the + buttons point into."""

    def __init__(self, text: str = "", screenshot=None, request: int = 0, lens_skipped: bool = False):
        self.text = text
        self.screenshot = screenshot
        self.request = request
        self.lens_skipped = lens_skipped  # for the translation's note
        self.translated = False  # started, so the link and the key don't start it twice
        self.shown: Result | None = None
        self.history = 0


class Bridge(QWebEngineUrlSchemeHandler):
    """Serves the page and answers its OCRClip calls. Lookups answer at once; Anki calls go to a thread."""

    _reply = pyqtSignal(object, bytes, bytes)

    def __init__(self, app):
        super().__init__()
        self.app = app
        self.html = b""
        self._reply.connect(self._send, Qt.ConnectionType.QueuedConnection)

    def requestStarted(self, job: QWebEngineUrlRequestJob):
        url = job.requestUrl()
        path = url.path()
        if path == "/page":
            self._send(job, b"text/html", self.html)
            return
        if not path.startswith("/api/"):
            job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)
            return
        method = path[5:]
        try:
            args = json.loads(QUrlQuery(url).queryItemValue("q", QUrl.ComponentFormattingOption.FullyDecoded))
        except ValueError:
            job.fail(QWebEngineUrlRequestJob.Error.RequestFailed)
            return
        if method in ("addToAnki", "ankiExisting"):
            # AnkiConnect can take a while; only the page waits for it, as it does for AnkiDroid.
            def run():
                self._reply.emit(job, b"application/json", self._call(method, args).encode())
            threading.Thread(target=run, daemon=True).start()
        else:
            self._send(job, b"application/json", self._call(method, args).encode())

    def _send(self, job, mime: bytes, data: bytes):
        try:
            buffer = QBuffer(job)
            buffer.setData(QByteArray(data))
            job.reply(mime, buffer)
        except RuntimeError:
            pass  # the page went away (a newer capture) before the answer came

    def _call(self, method: str, args: dict) -> str:
        session = self.app.session
        if method == "lookup":
            text = args["text"]
            return self._respond(session, lambda: self.app.dictionary.find(text, utf16_to_index(text, args["offset"])),
                                 text, args["offset"])
        if method == "lookupTerm":
            def find():
                r = self.app.dictionary.find(args["term"], 0)
                if r is not None and args.get("reading"):
                    r.entries.sort(key=lambda e: e.reading != args["reading"])
                return r
            return self._respond(session, find, args["term"], 0)
        if method == "setHistory":
            session.history = int(args["depth"])
            return "{}"
        if method == "ankiExisting":
            entries = session.shown.entries if session.shown else []
            existing, error = self.app.anki_existing({e.term for e in entries})
            # The shim hands the page script the list and shows the error, as Android has no such case.
            return json.dumps({"existing": [i for i, e in enumerate(entries) if e.term in existing], "error": error})
        if method == "addToAnki":
            return json.dumps(self.app.add_to_anki(session, args["entry"], args["definition"], args["sense"],
                                                   args["offset"]))
        if method == "translate":
            # After this request has been answered: translating touches the page, which is waiting on it.
            self.app.on_main(self.app.translate_now)
            return "{}"
        if method == "open":
            url = args["url"]
            if url.startswith(("https://", "http://")):
                subprocess.Popen(["xdg-open", url])
            return "{}"
        return "{}"

    def _respond(self, session, find, text: str, offset: int) -> str:
        try:
            result = find()
            session.shown = result
            if result is None:
                d = self.app.dictionary
                html = lookup_page.message(
                    f"The dictionary database couldn't be opened: {d.error}" if d.error else
                    "No dictionary database. Build one with tools/build_dict.py and set \"dictionary\" in "
                    "~/.config/ocrclip/settings.json to it.")
                return json.dumps({"length": 0, "html": html})
            start = utf16_to_index(text, offset)
            length = utf16_length(text[start:start + result.length])
            html = lookup_page.results(result, anki=self.app.anki.ready, styles=self.app.dictionary.styles)
            return json.dumps({"length": length, "html": html})
        except Exception as e:
            return json.dumps({"length": 0, "html": lookup_page.message(f"Lookup failed: {e}")})


class LookupWindow(QWidget):
    closed = pyqtSignal()

    def __init__(self, app):
        super().__init__()
        self.app = app
        self.setWindowTitle("OCR Clip")
        self.resize(560, 720)
        self.profile = QWebEngineProfile(self)  # off the record: nothing stored
        self.bridge = Bridge(app)
        self.profile.installUrlSchemeHandler(SCHEME, self.bridge)
        self.view = QWebEngineView(self)
        self.view.setPage(QWebEnginePage(self.profile, self.view))
        self.view.page().setBackgroundColor(Qt.GlobalColor.black)
        self.view.loadFinished.connect(self._loaded)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.view)
        self.setStyleSheet("background: black;")
        QShortcut(QKeySequence(Qt.Key.Key_Escape), self, self.escape)
        self.translate_shortcut = None
        self.set_translate_key(app.config["translate_key"])
        self.loads = 0
        self.ready = False
        self.pending = []

    def show_page(self, html: str):
        self.bridge.html = html.encode()
        self.loads += 1
        self.ready = False
        self.pending = []
        self.view.setUrl(QUrl(f"ocrclip://app/page?n={self.loads}"))
        self.show()
        self.raise_()
        self.activateWindow()
        self.view.setFocus()

    def run(self, script: str):
        """Runs [script] in the page, once it has loaded."""
        if self.ready:
            self.view.page().runJavaScript(script)
        else:
            self.pending.append(script)

    def set_translation(self, text: str):
        self.run(f"setTranslation({json.dumps(text)})")

    def set_note(self, note: str):
        self.run(f"setNote({json.dumps(note)})")

    def set_translate_key(self, key: str):
        if self.translate_shortcut is not None:
            self.translate_shortcut.setEnabled(False)
            self.translate_shortcut.deleteLater()
        sequence = QKeySequence(key or "")
        self.translate_shortcut = None
        if not sequence.isEmpty():
            self.translate_shortcut = QShortcut(sequence, self, self.app.translate_now)

    def translate_key_label(self) -> str:
        sequence = QKeySequence(self.app.config["translate_key"] or "")
        return sequence.toString(QKeySequence.SequenceFormat.NativeText)

    def offer_translation(self):
        """On-demand mode: the strip shows a link that translates (and says which key does)."""
        key = self.translate_key_label()
        self.run(f"setTranslateLink({json.dumps(f'Translate ({key})' if key else 'Translate')})")

    def set_translator(self, url: str):
        self.run(f"setTranslator({json.dumps(url)}, {json.dumps('Open the translator page')})")

    def _loaded(self, ok: bool):
        self.ready = True
        for script in self.pending:
            self.view.page().runJavaScript(script)
        self.pending = []

    def escape(self):
        # After following links, Esc steps back through them before it closes, like B on the handheld.
        if self.app.session.history > 0:
            self.view.page().runJavaScript("back()")
        else:
            self.hide()

    def hideEvent(self, event):
        super().hideEvent(event)
        self.closed.emit()
