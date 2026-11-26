"""
Google Lens OCR through the endpoint Chrome's Lens overlay uses (same request as owocr's "glens" engine), ported from
LensOcr.kt. Unofficial: Google can change or block it at any time. The protobuf messages are hand-encoded; field
numbers come from Chromium's lens_overlay_*.proto files.
"""

import math
import secrets
import urllib.request

from PyQt6.QtCore import QBuffer, QIODevice, Qt
from PyQt6.QtGui import QImage

ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
# Chrome's public API key, as used by owocr.
API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
MAX_PIXELS = 3_000_000
PLATFORM_WEB = 3
SURFACE_CHROMIUM = 4
AUTO_FILTER = 7


def recognize(image: QImage) -> list[str]:
    """The recognised lines, top to bottom. Blocking; call off the main thread."""
    image = _downscale(image)
    buffer = QBuffer()
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    image.save(buffer, "PNG")
    png = bytes(buffer.data())
    return _parse_lines(_post(_build_request(png, image.width(), image.height())))


def _downscale(image: QImage) -> QImage:
    pixels = image.width() * image.height()
    if pixels <= MAX_PIXELS:
        return image
    scale = math.sqrt(MAX_PIXELS / pixels)
    return image.scaled(int(image.width() * scale), int(image.height() * scale),
                        Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)


def _build_request(png: bytes, width: int, height: int) -> bytes:
    request_id = (Proto()
                  .varint(1, secrets.randbits(64))  # uuid
                  .varint(2, 0)  # sequence_id
                  .varint(3, 0)  # image_sequence_id
                  .bytes(4, secrets.token_bytes(16)))  # analytics_id
    locale = Proto().string(1, "ja").string(2, "Asia/Tokyo")
    filters = Proto().message(1, Proto().varint(1, AUTO_FILTER))
    client_context = (Proto()
                      .varint(1, PLATFORM_WEB)
                      .varint(2, SURFACE_CHROMIUM)
                      .message(4, locale)
                      .message(17, filters))
    request_context = Proto().message(3, request_id).message(4, client_context)
    image_data = (Proto()
                  .message(1, Proto().bytes(1, png))  # payload.image_bytes
                  .message(3, Proto().varint(1, width).varint(2, height)))  # image_metadata
    objects_request = Proto().message(1, request_context).message(3, image_data)
    return Proto().message(1, objects_request).data()


def _post(body: bytes) -> bytes:
    request = urllib.request.Request(ENDPOINT, data=body, method="POST", headers={
        "Content-Type": "application/x-protobuf",
        "X-Goog-Api-Key": API_KEY,
    })
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read()


def _parse_lines(response: bytes) -> list[str]:
    """objects_response(2).text(3).text_layout(1).paragraphs(1).lines(2).words(1).{plain_text(2), text_separator(3)}"""
    lines = []
    for objects in _fields(response, 2):
        for text in _fields(objects, 3):
            for layout in _fields(text, 1):
                for paragraph in _fields(layout, 1):
                    for line in _fields(paragraph, 2):
                        out = []
                        for word in _fields(line, 1):
                            for part in (2, 3):
                                found = _fields(word, part)
                                if found:
                                    out.append(found[0].decode("utf-8", "replace"))
                        lines.append("".join(out))
    return lines


class Proto:
    """Just enough protobuf writing for the Lens request."""

    def __init__(self):
        self.out = bytearray()

    def varint(self, field: int, value: int):
        self._varint(field << 3)
        self._varint(value)
        return self

    def bytes(self, field: int, value: bytes):
        self._varint(field << 3 | 2)
        self._varint(len(value))
        self.out += value
        return self

    def string(self, field: int, value: str):
        return self.bytes(field, value.encode())

    def message(self, field: int, value: "Proto"):
        return self.bytes(field, value.data())

    def data(self) -> bytes:
        return bytes(self.out)

    def _varint(self, value: int):
        value &= (1 << 64) - 1
        while value > 0x7F:
            self.out.append(value & 0x7F | 0x80)
            value >>= 7
        self.out.append(value)


def _fields(data: bytes, field: int) -> list[bytes]:
    """The length-delimited values of [field] in one protobuf message."""
    found = []
    pos = 0

    def varint():
        nonlocal pos
        result = shift = 0
        while True:
            b = data[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7

    while pos < len(data):
        key = varint()
        number, wire = key >> 3, key & 7
        if wire == 0:
            varint()
        elif wire == 1:
            pos += 8
        elif wire == 2:
            length = varint()
            if number == field:
                found.append(data[pos:pos + length])
            pos += length
        elif wire == 5:
            pos += 4
        else:
            raise IOError("Unsupported protobuf wire type in Lens response")
    return found
