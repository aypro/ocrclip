"""
Offline Japanese → English with a CAT-Translate GGUF, through llama.cpp's llama-server (CUDA on the PC, where the
Android app links llama.cpp itself): started on first use, streamed from, stopped after 10 idle minutes.
"""

import json
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

from .config import CACHE_DIR

IDLE_SECONDS = 600
LOAD_TIMEOUT = 180


class TranslatorError(Exception):
    pass


def prompt(text: str) -> str:
    """CAT-Translate's chat format written out, as LocalTranslator.prompt writes it."""
    return f"<|user|>Translate the following Japanese text into English.\n\n{text}</s><|assistant|>"


class LocalTranslator:
    def __init__(self, config):
        self.config = config
        self.lock = threading.Lock()  # one translation at a time, like the Android app's single thread
        self.process = None
        self.port = 0
        self.last_used = 0.0
        self.log = CACHE_DIR / "llama-server.log"

    @property
    def available(self) -> bool:
        return self.config.path_of("translate_model").exists()

    def translate(self, text: str, loading, progress):
        """
        Streams the translation of [text]: [loading]() when the model has to be loaded first, [progress](partial)
        with the text so far, which returns False to stop. Blocking; raises TranslatorError.
        """
        with self.lock:
            self.last_used = time.monotonic()
            if self.process is None or self.process.poll() is not None:
                loading()
                self._start()
            try:
                self._generate(text, progress)
            finally:
                self.last_used = time.monotonic()

    def _start(self):
        model = self.config.path_of("translate_model")
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.port = s.getsockname()[1]
        self.log.parent.mkdir(parents=True, exist_ok=True)
        with open(self.log, "w") as log:
            try:
                self.process = subprocess.Popen(
                    [self.config["llama_server"], "-m", str(model), "--host", "127.0.0.1", "--port", str(self.port),
                     "-ngl", "99", "-c", "4096", "-np", "1", "--no-webui"],
                    stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                )
            except OSError as e:
                raise TranslatorError(f"can't run {self.config['llama_server']}: {e}") from e
        deadline = time.monotonic() + LOAD_TIMEOUT
        while True:
            if self.process.poll() is not None:
                tail = self.log.read_text(errors="replace").strip().splitlines()[-1:] or ["no output"]
                self.process = None
                raise TranslatorError(f"llama-server exited: {tail[0]}")
            try:
                with urllib.request.urlopen(self._url("/health"), timeout=2) as r:
                    if r.status == 200:
                        break
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                pass  # still loading (503) or not listening yet
            if time.monotonic() > deadline:
                self._stop()
                raise TranslatorError("the model took too long to load")
            time.sleep(0.2)
        # Same check as LocalTranslator.validate: only CAT-Translate's chat format is written out above.
        with urllib.request.urlopen(self._url("/props"), timeout=5) as r:
            template = json.loads(r.read()).get("chat_template") or ""
        if "<|user|>" not in template or "<|assistant|>" not in template:
            self._stop()
            raise TranslatorError("that model isn't CAT-Translate (its chat template doesn't match)")

    def _generate(self, text: str, progress):
        body = json.dumps({
            "prompt": prompt(text), "n_predict": 1024, "temperature": 0, "top_k": 1, "stream": True,
            "stop": ["</s>"], "cache_prompt": False,
        }).encode()
        request = urllib.request.Request(self._url("/completion"), data=body,
                                         headers={"Content-Type": "application/json"})
        out = ""
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                for raw in response:
                    line = raw.decode("utf-8").strip()
                    if not line.startswith("data:"):
                        continue
                    chunk = json.loads(line[5:])
                    out += chunk.get("content", "")
                    if not progress(out.strip()):
                        return  # closing the response stops generation
                    if chunk.get("stop"):
                        return
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            raise TranslatorError(str(e)) from e

    def _url(self, path: str) -> str:
        return f"http://127.0.0.1:{self.port}{path}"

    def stop_if_idle(self):
        if not self.lock.acquire(blocking=False):
            return
        try:
            if self.process is not None and time.monotonic() - self.last_used > IDLE_SECONDS:
                self._stop()
        finally:
            self.lock.release()

    def close(self):
        with self.lock:
            self._stop()

    def _stop(self):
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.process = None
