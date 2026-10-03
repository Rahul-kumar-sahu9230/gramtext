import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent  # .../backend
load_dotenv(BASE_DIR / ".env")


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _list(name: str, default: str) -> list[str]:
    return [v.strip() for v in os.getenv(name, default).split(",") if v.strip()]


GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
# Flash-Lite + low thinking: ~2 s per scan and exact on our tests (Flash default took ~17 s).
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-flash-lite-latest").strip()
GEMINI_FALLBACK_MODELS = _list("GEMINI_FALLBACK_MODELS", "gemini-flash-latest,gemini-3.8-flash")
# "low" keeps OCR fast; empty = model default (much slower).
GEMINI_THINKING = os.getenv("GEMINI_THINKING", "low").strip().lower()

# gTTS (Google Translate voices, no key). "co.in" gives an Indian English accent.
TTS_TLD = os.getenv("TTS_TLD", "co.in").strip() or "co.in"
TTS_SLOW = os.getenv("TTS_SLOW", "false").strip().lower() in ("1", "true", "yes")

# Our trained STR model (ml/export.py). Gemini is the backup.
OCR_ENGINE = os.getenv("OCR_ENGINE", "auto").strip().lower()  # auto | str | gemini
if OCR_ENGINE not in ("auto", "str", "gemini"):
    OCR_ENGINE = "auto"
STR_MODEL_PATH = str(BASE_DIR / os.getenv("STR_MODEL_PATH", "models/gramtext_str.pt").strip())
try:
    STR_MIN_CONFIDENCE = float(os.getenv("STR_MIN_CONFIDENCE", "0.85"))
except ValueError:
    STR_MIN_CONFIDENCE = 0.85

DB_PATH = BASE_DIR / os.getenv("DB_PATH", "data/gramtext.db").strip()

MAX_UPLOAD_MB = _int("MAX_UPLOAD_MB", 10)
TTS_MAX_CHARS = _int("TTS_MAX_CHARS", 1500)
FRONTEND_ORIGINS = _list(
    "FRONTEND_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173"
)
# Also allow the web demo from phones/laptops on the local network (10.x, 172.16-31.x, 192.168.x).
FRONTEND_ORIGIN_REGEX = os.getenv(
    "FRONTEND_ORIGIN_REGEX",
    r"http://(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)(:\d+)?",
).strip() or None

