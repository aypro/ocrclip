"""
On-device OCR through ocr_worker.py, run under another Python (the owocr uv tool, which has meikiocr): started on
first use, kept warm, stopped after 10 idle minutes.
"""

import json
import subprocess
import threading
import time
from pathlib import Path

WORKER = Path(__file__).with_name("ocr_worker.py")
IDLE_SECONDS = 600


class OfflineOcrError(Exception):
    pass


class OfflineOcr:
    def __init__(self, config):
        self.config = config
        self.lock = threading.Lock()
        self.process = None
        self.last_used = 0.0

    @property
    def available(self) -> bool:
        return self.config.path_of("offline_ocr_python").exists()

    def recognize(self, path: Path) -> list[str]:
        """The lines read from the image at [path], in reading order. Blocking."""
        with self.lock:
            self.last_used = time.monotonic()
            process = self._start()
            try:
                process.stdin.write(json.dumps({"path": str(path)}) + "\n")
                process.stdin.flush()
                reply = self._read(process)
            except (OSError, ValueError) as e:
                self._stop()
                raise OfflineOcrError(f"on-device OCR stopped: {e}") from e
            self.last_used = time.monotonic()
        if "error" in reply:
            raise OfflineOcrError(reply["error"])
        return reply["lines"]

    def _start(self):
        if self.process is not None and self.process.poll() is None:
            return self.process
        python = self.config.path_of("offline_ocr_python")
        if not python.exists():
            raise OfflineOcrError(f"no on-device OCR set up ({python} is missing)")
        self.process = subprocess.Popen(
            [str(python), str(WORKER)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", bufsize=1,
        )
        ready = self._read(self.process)
        if "error" in ready:
            self._stop()
            raise OfflineOcrError(ready["error"])
        return self.process

    @staticmethod
    def _read(process) -> dict:
        line = process.stdout.readline()
        if not line:
            raise ValueError("the worker exited")
        return json.loads(line)

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
        try:
            self.process.stdin.close()
            self.process.wait(timeout=3)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
        self.process = None
