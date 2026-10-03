"""One-shot "read this aloud": OCR and TTS overlapped and streamed to the client.

Events (one JSON object per line, application/x-ndjson):
    {"type": "text",  "text": "<all text so far>", "engine": "gemini"}
    {"type": "audio", "seq": 0, "text": "<sentence>", "mime": "audio/mpeg", "data": "<base64>"}
    {"type": "done",  "text": ..., "language": ..., "engine": ..., "confidence": ...,
                      "scan_id": ..., "ocr_ms": ..., "first_audio_ms": ...}
    {"type": "error", "detail": "<friendly message>", "stage": "ocr" | "tts"}

Each sentence is sent to gTTS the moment OCR finishes it, several in parallel, and
audio is streamed back in order - so speech starts ~0.5 s after the first line is read.
"""
import base64
import re
import time
from collections.abc import Iterator
from concurrent.futures import Future, ThreadPoolExecutor

from PIL import Image

from app import db
from app.errors import ServiceError
from app.services.gtts_tts import take_segments, text_to_speech
from app.services.ocr_pipeline import stream_ocr

DEVANAGARI = re.compile(r"[ऀ-ॿ]")
TTS_WORKERS = 6
_pool = ThreadPoolExecutor(max_workers=TTS_WORKERS, thread_name_prefix="tts")


def warm_up() -> None:
    """Give every TTS worker thread a live connection before the first user arrives."""
    from app.services import gemini_ocr, gtts_tts

    for _ in range(TTS_WORKERS):
        _pool.submit(gtts_tts.warm_up)
    gemini_ocr.warm_up()


def read_stream(image: Image.Image, client_id: str | None) -> Iterator[dict]:
    start = time.perf_counter()
    ms = lambda: int((time.perf_counter() - start) * 1000)
    jobs: list[tuple[str, Future]] = []
    sent = 0
    text, pending = "", ""
    engine, confidence = None, None
    ocr_ms = first_audio_ms = None
    tts_failed = False

    def submit(segments: list[str]) -> None:
        for segment in segments:
            jobs.append((segment, _pool.submit(text_to_speech, segment)))

    def drain(block: bool) -> Iterator[dict]:
        nonlocal sent, first_audio_ms, tts_failed
        while sent < len(jobs) and not tts_failed:
            segment, future = jobs[sent]
            if not block and not future.done():
                return
            try:
                audio = future.result()
            except ServiceError as exc:
                tts_failed = True
                yield {"type": "error", "stage": "tts", "detail": exc.message}
                return
            if first_audio_ms is None:
                first_audio_ms = ms()
            yield {"type": "audio", "seq": sent, "text": segment, "mime": "audio/mpeg",
                   "data": base64.b64encode(audio).decode("ascii")}
            sent += 1

    try:
        for piece, engine, confidence in stream_ocr(image):
            text += piece
            pending += piece
            yield {"type": "text", "text": text.strip(), "engine": engine}
            segments, pending = take_segments(pending, final=False)
            submit(segments)
            yield from drain(block=False)
        ocr_ms = ms()
        segments, pending = take_segments(pending, final=True)
        submit(segments)
        yield from drain(block=True)
    except ServiceError as exc:
        db.log_scan(None, None, ms(), 0, "und", False, client_id)
        yield {"type": "error", "stage": "ocr", "detail": exc.message}
        return
    finally:
        for _, future in jobs[sent:]:
            future.cancel()

    text = text.strip()
    language = ("hi" if DEVANAGARI.search(text) else "en") if text else "und"
    scan_id = db.log_scan(engine, confidence, ocr_ms, len(text), language, bool(text), client_id)
    if jobs:
        db.log_tts(len(text), first_audio_ms or ms(), not tts_failed)
    yield {"type": "done", "text": text, "language": language, "engine": engine,
           "confidence": confidence, "scan_id": scan_id, "ocr_ms": ocr_ms,
           "first_audio_ms": first_audio_ms,
           "message": "Text extracted successfully." if text else "No readable text was detected."}
