"""
ocrclip-desktop [run|once FILE…|toggle|pause|resume|show|hide|region|quit] [--offline]

Without a command, or with run, starts the app unless it's already running. The other commands go to the running app
(once starts it first if needed).
"""

import argparse
import json
import signal
import sys

COMMANDS = ("run", "once", "toggle", "pause", "resume", "show", "hide", "region", "quit")


def main():
    parser = argparse.ArgumentParser(prog="ocrclip-desktop", description="OCR Clip for new screenshots.")
    parser.add_argument("command", nargs="?", default="run", choices=COMMANDS)
    parser.add_argument("args", nargs="*", help="image files, for once")
    parser.add_argument("--offline", action="store_true",
                        help="skip Google Lens and DeepL, as without a connection (for testing)")
    options = parser.parse_args()

    from PyQt6.QtCore import QCoreApplication, Qt
    from PyQt6.QtNetwork import QLocalSocket

    from .app import SERVER_NAME

    socket = QLocalSocket()
    socket.connectToServer(SERVER_NAME)
    if socket.waitForConnected(500):
        if options.command == "run":
            print("ocrclip-desktop is already running")
            return 0
        args = [str(__import__("pathlib").Path(a).expanduser().resolve()) for a in options.args]
        socket.write((json.dumps({"command": options.command, "args": args}) + "\n").encode())
        socket.flush()
        socket.waitForReadyRead(5000)
        reply = bytes(socket.readAll()).decode().strip()
        if reply != "ok":
            print(reply)
            return 1
        return 0
    if options.command not in ("run", "once"):
        print("ocrclip-desktop isn't running")
        return 1

    from .window import register_scheme
    register_scheme()
    QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)
    from PyQt6.QtWidgets import QApplication
    qapp = QApplication(sys.argv[:1])
    qapp.setApplicationName("ocrclip")
    qapp.setApplicationDisplayName("OCR Clip")
    qapp.setDesktopFileName("ocrclip")  # the Wayland app id, which Hyprland window rules match as the class
    qapp.setQuitOnLastWindowClosed(False)
    signal.signal(signal.SIGINT, lambda *_: qapp.quit())
    signal.signal(signal.SIGTERM, lambda *_: qapp.quit())

    from .app import App
    app = App(qapp, offline=options.offline)
    if options.command == "once":
        app.command("once", options.args)
    # Python only sees signals between Qt events; a timer keeps it looking.
    from PyQt6.QtCore import QTimer
    timer = QTimer()
    timer.timeout.connect(lambda: None)
    timer.start(500)
    return qapp.exec()


if __name__ == "__main__":
    sys.exit(main())
