import json
import re
import threading
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

from app import db
from app.config import (
    FRONTEND_ORIGIN_REGEX,
    FRONTEND_ORIGINS,
    GEMINI_API_KEY,
    MAX_UPLOAD_MB,
    OCR_ENGINE,
    TTS_MAX_CHARS,
)
from app.errors import ServiceError
from app.services import detector, str_model
from app.services.gtts_tts import text_to_speech
from app.services.ocr_pipeline import run_ocr
from app.services.reader import read_stream
from app.services.reader import warm_up as read_warm_up
from app.utils.validation import prepare_image


@asynccontextmanager
async def lifespan(_):
    db.init()
    threading.Thread(target=read_warm_up, daemon=True).start()  # first scan is fast too
    if str_model.is_available():  # load models in the background so the first scan is fast
        threading.Thread(target=lambda: (detector.warm_up(), str_model.model_info()), daemon=True).start()
    yield


app = FastAPI(title="GramText API", version="3.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=FRONTEND_ORIGINS,
    allow_origin_regex=FRONTEND_ORIGIN_REGEX,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}
DEVANAGARI = re.compile(r"[ऀ-ॿ]")


class TTSRequest(BaseModel):
    text: str


class FeedbackRequest(BaseModel):
    scan_id: int | None = None
    helpful: bool
    corrected_text: str | None = Field(default=None, max_length=5000)
    comment: str | None = Field(default=None, max_length=1000)


@app.exception_handler(ServiceError)
async def service_error_handler(_, exc: ServiceError):
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.message})


def _ms(start: float) -> int:
    return int((time.perf_counter() - start) * 1000)


@app.get("/api/health")
def health():
    str_ready = str_model.is_available()
    return {
        "success": True,
        "service": "GramText API",
        "status": "healthy",
        "ocr_ready": str_ready or bool(GEMINI_API_KEY),
        "ocr_engine": OCR_ENGINE,
        "str_model_ready": str_ready,
        "gemini_ready": bool(GEMINI_API_KEY),
        "tts_ready": True,  # gTTS needs no key
        "max_upload_mb": MAX_UPLOAD_MB,
        "tts_max_chars": TTS_MAX_CHARS,
    }


# Plain `def` (not async): FastAPI runs it in a worker thread, so slow model or
# Gemini calls do not block other requests.
def _read_upload(file: UploadFile):
    if file.content_type not in ALLOWED_TYPES:
        raise HTTPException(400, "Please upload a JPG, PNG or WEBP image.")

    max_bytes = MAX_UPLOAD_MB * 1024 * 1024
    # Starlette has already buffered the upload; reading limit+1 is enough to detect oversize.
    data = file.file.read(max_bytes + 1)

    if not data:
        raise HTTPException(400, "Uploaded image is empty.")
    if len(data) > max_bytes:
        raise HTTPException(413, f"Image must be smaller than {MAX_UPLOAD_MB} MB.")
    return prepare_image(data)  # real-image check, EXIF rotation, resize


@app.post("/api/read")
def read_aloud(file: UploadFile = File(...), x_client_id: str | None = Header(default=None)):
    """Fast path used by the apps: OCR + speech in one streamed response (see reader.py)."""
    image = _read_upload(file)
    lines = (json.dumps(event, ensure_ascii=False) + "\n" for event in read_stream(image, x_client_id))
    return StreamingResponse(lines, media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/api/ocr")
def ocr(file: UploadFile = File(...), x_client_id: str | None = Header(default=None)):
    image = _read_upload(file)
    start = time.perf_counter()
    try:
        result = run_ocr(image)
    except ServiceError:
        db.log_scan(None, None, _ms(start), 0, "und", False, x_client_id)
        raise

    text = result["text"]
    language = ("hi" if DEVANAGARI.search(text) else "en") if text else "und"
    scan_id = db.log_scan(result["engine"], result["confidence"], _ms(start), len(text),
                          language, bool(text), x_client_id)
    base = {"scan_id": scan_id, "engine": result["engine"], "confidence": result["confidence"]}

    if not text:
        return {**base, "success": False, "text": "", "language": "und",
                "message": "No readable text was detected."}
    return {**base, "success": True, "text": text, "language": language,
            "message": "Text extracted successfully."}


@app.post("/api/tts")
def tts(request: TTSRequest):  # plain def: gTTS is blocking
    text = request.text.strip()

    if not text:
        raise HTTPException(400, "Text is required.")
    if len(text) > TTS_MAX_CHARS:
        raise HTTPException(
            400, f"Text is too long. Please keep it under {TTS_MAX_CHARS} characters."
        )

    start = time.perf_counter()
    try:
        audio = text_to_speech(text)
    except ServiceError:
        db.log_tts(len(text), _ms(start), False)
        raise
    db.log_tts(len(text), _ms(start), True)
    return Response(
        content=audio,
        media_type="audio/mpeg",
        headers={"Content-Disposition": "inline; filename=gramtext.mp3"},
    )


@app.post("/api/feedback")
def feedback(request: FeedbackRequest, x_client_id: str | None = Header(default=None)):
    corrected = (request.corrected_text or "").strip() or None
    comment = (request.comment or "").strip() or None
    feedback_id = db.add_feedback(request.scan_id, request.helpful, corrected, comment, x_client_id)
    return {"success": True, "feedback_id": feedback_id, "message": "Thank you for your feedback."}


@app.get("/api/metrics")
def metrics():
    """Impact metrics for the field pilot report (no personal data)."""
    return {**db.metrics(), "model": str_model.model_info()}
