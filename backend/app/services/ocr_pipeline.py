"""OCR orchestration: our STR model first, Gemini as the backup.

OCR_ENGINE=auto   STR model; Gemini when the model is missing or not confident
OCR_ENGINE=str    STR model only (for evaluation / viva demo)
OCR_ENGINE=gemini Gemini only
"""
import logging
from collections.abc import Iterator

from PIL import Image

from app.config import GEMINI_API_KEY, OCR_ENGINE, STR_MIN_CONFIDENCE
from app.errors import ServiceError
from app.services import detector, str_model
from app.services.gemini_ocr import stream_text as gemini_stream

log = logging.getLogger("gramtext.ocr")


def _run_str(image: Image.Image) -> tuple[str, float]:
    lines = detector.detect_lines(image)
    if not lines:
        return "", 0.0
    flat = [crop for line in lines for crop in line]
    results = iter(str_model.recognize(flat))

    out_lines, total_conf, total_chars = [], 0.0, 0
    for line in lines:
        words = []
        for _ in line:
            text, conf = next(results)
            if text.strip():
                words.append(text.strip())
                total_conf += conf * len(text)
                total_chars += len(text)
        if words:
            out_lines.append(" ".join(words))
    confidence = total_conf / total_chars if total_chars else 0.0
    return "\n".join(out_lines), confidence


def stream_ocr(image: Image.Image) -> Iterator[tuple[str, str, float | None]]:
    """Yield (text_piece, engine, confidence). Our model returns everything at once;
    Gemini streams pieces as it reads, so speech can start before OCR finishes."""
    str_text, str_conf, str_error = "", 0.0, None

    if OCR_ENGINE in ("auto", "str") and str_model.is_available():
        try:
            str_text, str_conf = _run_str(image)
        except ServiceError as exc:
            str_error = exc
        except Exception as exc:  # never let a model bug crash the request
            log.exception("STR model failed")
            str_error = ServiceError("The text recognition model failed.", 500)
            str_error.__cause__ = exc

        if OCR_ENGINE == "str" or (str_text and str_conf >= STR_MIN_CONFIDENCE):
            if str_error and not str_text:
                raise str_error
            if str_text:
                yield str_text, "str", round(str_conf, 3)
            return
    elif OCR_ENGINE == "str":
        raise ServiceError("The text recognition model is not installed on the server.", 503)

    # Backup: Gemini (also the path when no trained model is installed yet).
    if not GEMINI_API_KEY and str_text:
        yield str_text, "str", round(str_conf, 3)
        return
    got_any = False
    try:
        for piece in gemini_stream(image):
            got_any = True
            yield piece, "gemini", None
    except ServiceError:
        if str_text and not got_any:  # Gemini down: a low-confidence answer beats no answer
            yield str_text, "str", round(str_conf, 3)
            return
        raise


def run_ocr(image: Image.Image) -> dict:
    """Returns {"text", "engine", "confidence"}; raises ServiceError if nothing works."""
    text, engine, confidence = "", None, None
    for piece, engine, confidence in stream_ocr(image):
        text += piece
    return {"text": text.strip(), "engine": engine, "confidence": confidence}
