"""Text detection (DBNet, pretrained) -> word crops grouped into lines.

Uses onnxtr (docTR's ONNX build: no torch, fits Render's free plan) and falls back to
docTR (PyTorch) if only that is installed. Both give the same boxes."""
import threading

import numpy as np
from PIL import Image

from app.errors import ServiceError

_predictor = None
_lock = threading.Lock()
MIN_SCORE = 0.3


def _get_predictor():
    global _predictor
    with _lock:
        if _predictor is None:  # heavy import, load once
            try:
                from onnxtr.models import detection_predictor

                _predictor = detection_predictor(arch="db_mobilenet_v3_large", assume_straight_pages=True)
            except ImportError:
                from doctr.models import detection_predictor

                _predictor = detection_predictor(
                    arch="db_mobilenet_v3_large", pretrained=True, assume_straight_pages=True
                )
    return _predictor


def warm_up() -> None:
    _get_predictor()


def _merge_fragments(boxes: list[list[float]], median_h: float) -> list[list[float]]:
    """Fold tiny boxes (stray matras such as ें / ों) into the word they overlap."""
    big = [b for b in boxes if b[3] - b[1] >= 0.6 * median_h]
    small = [b for b in boxes if b[3] - b[1] < 0.6 * median_h]
    for s in small:
        sx = (s[0] + s[2]) / 2
        best = None
        for b in big:
            near_x = b[0] - 0.2 * median_h <= sx <= b[2] + 0.2 * median_h
            near_y = s[3] >= b[1] - 0.6 * median_h and s[1] <= b[3]
            if near_x and near_y:
                best = b
                break
        if best:
            best[0], best[1] = min(best[0], s[0]), min(best[1], s[1])
            best[2], best[3] = max(best[2], s[2]), max(best[3], s[3])
        else:
            big.append(s)
    return big


def detect_lines(image: Image.Image) -> list[list[Image.Image]]:
    """Return lines (top to bottom), each a list of word crops (left to right)."""
    rgb = image.convert("RGB")
    W, H = rgb.size
    try:
        result = _get_predictor()([np.asarray(rgb)])[0]
        if isinstance(result, dict):  # docTR returns {"words": boxes}, onnxtr returns boxes
            result = result["words"]
    except Exception as exc:
        raise ServiceError("The text detector failed on this image.", 500) from exc

    boxes = [[b[0] * W, b[1] * H, b[2] * W, b[3] * H] for b in result if b[4] >= MIN_SCORE]
    if not boxes:
        return []
    median_h = float(np.median([b[3] - b[1] for b in boxes]))
    boxes = _merge_fragments(boxes, median_h)

    # Group into lines by vertical centre, then sort each line left to right.
    boxes.sort(key=lambda b: (b[1] + b[3]) / 2)
    lines: list[list[list[float]]] = []
    for b in boxes:
        yc = (b[1] + b[3]) / 2
        if lines:
            line = lines[-1]
            line_yc = np.mean([(x[1] + x[3]) / 2 for x in line])
            if abs(yc - line_yc) < 0.5 * median_h:
                line.append(b)
                continue
        lines.append([b])

    crops = []
    for line in lines:
        row = []
        for x0, y0, x1, y1 in sorted(line, key=lambda b: b[0]):
            h = y1 - y0
            # Devanagari matras sit above the head line; the detector often clips them.
            box = (
                max(0, int(x0 - 0.08 * h)), max(0, int(y0 - 0.35 * h)),
                min(W, int(x1 + 0.08 * h)), min(H, int(y1 + 0.15 * h)),
            )
            if box[2] - box[0] >= 4 and box[3] - box[1] >= 4:
                row.append(rgb.crop(box))
        if row:
            crops.append(row)
    return crops
