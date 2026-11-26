"""Drag a capture region over the last screenshot (RegionActivity.kt), kept as fractions of the image."""

from PyQt6.QtCore import QPointF, QRectF, Qt, pyqtSignal
from PyQt6.QtGui import QColor, QImage, QKeySequence, QPainter, QPen, QShortcut
from PyQt6.QtWidgets import QWidget


class RegionPicker(QWidget):
    picked = pyqtSignal(object)  # [l, t, r, b] or None to clear

    def __init__(self, image: QImage, region):
        super().__init__()
        self.setWindowTitle("OCR Clip region")
        self.image = image
        self.region = QRectF(*region[:2], region[2] - region[0], region[3] - region[1]) if region else None
        self.start = None
        self.resize(min(image.width(), 1600), min(image.height(), 1200))
        QShortcut(QKeySequence(Qt.Key.Key_Escape), self, self.close)
        QShortcut(QKeySequence(Qt.Key.Key_Return), self, self.save)
        QShortcut(QKeySequence(Qt.Key.Key_Enter), self, self.save)
        QShortcut(QKeySequence(Qt.Key.Key_Delete), self, self.clear)
        QShortcut(QKeySequence(Qt.Key.Key_Backspace), self, self.clear)

    def _frame(self) -> QRectF:
        """Where the image is drawn: fitted and centred."""
        scale = min(self.width() / self.image.width(), (self.height() - 40) / self.image.height())
        w, h = self.image.width() * scale, self.image.height() * scale
        return QRectF((self.width() - w) / 2, 40 + (self.height() - 40 - h) / 2, w, h)

    def _fraction(self, pos) -> QPointF:
        f = self._frame()
        return QPointF(min(max((pos.x() - f.x()) / f.width(), 0), 1), min(max((pos.y() - f.y()) / f.height(), 0), 1))

    def paintEvent(self, event):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor("#111"))
        p.setPen(QColor("#ccc"))
        p.drawText(QRectF(0, 0, self.width(), 40), Qt.AlignmentFlag.AlignCenter,
                   "Drag over the text box · Enter saves · Delete clears the region · Esc cancels")
        f = self._frame()
        p.drawImage(f, self.image)
        if self.region:
            r = QRectF(f.x() + self.region.x() * f.width(), f.y() + self.region.y() * f.height(),
                       self.region.width() * f.width(), self.region.height() * f.height())
            p.fillRect(r, QColor(108, 180, 255, 50))
            p.setPen(QPen(QColor("#6cb4ff"), 2))
            p.drawRect(r)

    def mousePressEvent(self, event):
        self.start = self._fraction(event.position())
        self.region = None
        self.update()

    def mouseMoveEvent(self, event):
        if self.start is not None:
            self.region = QRectF(self.start, self._fraction(event.position())).normalized()
            self.update()

    def mouseReleaseEvent(self, event):
        self.mouseMoveEvent(event)
        self.start = None

    def save(self):
        r = self.region
        if r is not None and r.width() > 0.01 and r.height() > 0.01:
            self.picked.emit([round(v, 4) for v in (r.left(), r.top(), r.right(), r.bottom())])
        self.close()

    def clear(self):
        self.picked.emit(None)
        self.close()
