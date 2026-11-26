"""
On-device OCR for when Google Lens can't be reached, with meikiocr (line detection and recognition trained on video
game text). Not run by the app's own interpreter: it runs under the Python of the owocr uv tool, installed with
`uv tool install 'owocr[screenai]' --with meikiocr`, so it has no imports from this package.

Protocol: one JSON object per line on stdin, {"path": "<image>"}; one per line on stdout, {"lines": [...]} or
{"error": "..."}. The first line out is {"ready": true} once the models are loaded, or {"error": ...} if they can't be.
"""

import json
import sys

# Libraries print to stdout (progress, warnings); only the protocol may go there.
out = sys.stdout
sys.stdout = sys.stderr


def send(obj):
    out.write(json.dumps(obj, ensure_ascii=False) + "\n")
    out.flush()


def ordered(results):
    """Lines in reading order: top to bottom, or right to left when most lines are vertical."""
    lines = []
    for r in results:
        text = r.get("text", "").replace("�", " ")
        chars = r.get("chars") or []
        if not text.strip() or not chars:
            continue
        x = min(c["bbox"][0] for c in chars)
        y = min(c["bbox"][1] for c in chars)
        x2 = max(c["bbox"][2] for c in chars)
        lines.append((bool(r.get("is_vertical")), x, y, x2, text))
    if sum(1 for line in lines if line[0]) > len(lines) / 2:
        lines.sort(key=lambda line: (-line[3], line[2]))
    else:
        lines.sort(key=lambda line: (line[2], line[1]))
    return [line[4] for line in lines]


def main():
    try:
        import numpy as np
        from PIL import Image
        from meikiocr import MeikiOCR
        ocr = MeikiOCR()
    except Exception as e:  # a missing package or model, an upgraded meikiocr...
        send({"error": f"on-device OCR couldn't start: {e}"})
        return
    send({"ready": True})
    for line in sys.stdin:
        try:
            path = json.loads(line)["path"]
            with Image.open(path) as img:
                image = np.array(img.convert("RGB"))
            # Same punctuation weighting as owocr's meikiocr engine.
            send({"lines": ordered(ocr.run_ocr(image, punct_conf_factor=0.2))})
        except Exception as e:
            send({"error": str(e)})


if __name__ == "__main__":
    main()
