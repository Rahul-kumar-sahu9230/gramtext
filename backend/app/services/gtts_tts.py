import base64
import re
import threading
from io import BytesIO

import requests
from gtts import gTTS
from gtts.tts import gTTSError

from app.config import TTS_SLOW, TTS_TLD
from app.errors import ServiceError

DEVANAGARI = re.compile(r"[ऀ-ॿ]")
LATIN = re.compile(r"[A-Za-z]")


def split_by_script(text: str) -> list[tuple[str, str]]:
    """Split mixed text into (lang, chunk) runs: Devanagari -> "hi", Latin -> "en".

    Digits, spaces and punctuation stay with the current run, so "दवा 2 बार" is one
    Hindi chunk and labels like "Paracetamol 500mg" stay one English chunk.
    """
    current = "hi" if DEVANAGARI.search(text) else "en"
    runs: list[tuple[str, str]] = []
    buf = ""
    for ch in text:
        lang = "hi" if DEVANAGARI.match(ch) else "en" if LATIN.match(ch) else None
        if lang and lang != current and buf.strip():
            runs.append((current, buf))
            buf = ""
        if lang:
            current = lang
        buf += ch
    if buf.strip():
        runs.append((current, buf))
    return runs


_BOUNDARY = re.compile(r"\n|[।॥!?](?=\s|$)|\.(?=\s|$)")
MAX_SEGMENT = 100  # gTTS sends one request per <=100 chars; keep it to one so segments run in parallel


def take_segments(pending: str, final: bool) -> tuple[list[str], str]:
    """Cut complete sentences/lines off the front of `pending` so each can be spoken
    as soon as it is recognised. Returns (segments, unfinished_rest)."""
    segments, start = [], 0
    for m in _BOUNDARY.finditer(pending):
        segment = pending[start : m.end()].strip()
        if segment:
            segments.append(segment)
        start = m.end()
    rest = pending[start:]
    while len(rest) > MAX_SEGMENT:  # long text with no punctuation: cut at a space
        cut = rest.rfind(" ", 0, MAX_SEGMENT)
        cut = cut if cut > 0 else MAX_SEGMENT
        segments.append(rest[:cut].strip())
        rest = rest[cut:]
    if final and rest.strip():
        segments.append(rest.strip())
        rest = ""
    return [s for s in segments if any(ch.isalnum() for ch in s)], rest


def _map_error(exc: Exception) -> ServiceError:
    raw = str(exc).lower()
    if "429" in raw or "too many" in raw:
        return ServiceError("The free API limit has been reached. Please try again later.", 429)
    if "timed out" in raw or "timeout" in raw:
        return ServiceError("The request took too long. Please try again.", 504)
    return ServiceError("The voice service is temporarily unavailable.", 502)


_local = threading.local()
_AUDIO = re.compile(r'jQ1olc","\[\\"(.*)\\"]')


def _session() -> requests.Session:
    """One keep-alive HTTPS session per worker thread. gTTS itself opens a new
    connection (TLS handshake) for every request; reusing it saves ~0.2-0.4 s each."""
    session = getattr(_local, "session", None)
    if session is None:
        session = _local.session = requests.Session()
    return session


def _speak(chunk: str, lang: str, out: BytesIO) -> None:
    tts = gTTS(chunk, lang=lang, tld=TTS_TLD, slow=TTS_SLOW)
    for prepared in tts._prepare_requests():  # gTTS builds the Google Translate TTS requests
        response = _session().send(prepared, timeout=(5, 30))
        if response.status_code == 429:
            raise ServiceError("The free API limit has been reached. Please try again later.", 429)
        if not response.ok:
            raise ServiceError("The voice service is temporarily unavailable.", 502)
        for line in response.text.splitlines():
            match = _AUDIO.search(line)
            if match:
                out.write(base64.b64decode(match.group(1)))


def text_to_speech(text: str) -> bytes:
    """Google Translate TTS via gTTS (no key). Mixed Hindi/English is read in both voices."""
    audio = BytesIO()
    try:
        for lang, chunk in split_by_script(text):
            _speak(chunk, lang, audio)
    except ServiceError:
        raise
    except (gTTSError, AssertionError, ValueError) as exc:
        if isinstance(exc, gTTSError):
            raise _map_error(exc) from exc
        raise ServiceError("The voice service could not read this text.", 400) from exc
    except requests.Timeout as exc:
        raise ServiceError("The request took too long. Please try again.", 504) from exc
    except Exception as exc:  # other network errors
        raise _map_error(exc) from exc

    data = audio.getvalue()
    if not data:
        raise ServiceError("The voice service could not read this text.", 400)
    return data  # MP3 frames from each chunk concatenate into one playable MP3


def warm_up() -> None:
    """Open this thread's keep-alive connection so the first real request is fast."""
    try:
        text_to_speech("नमस्ते")
    except ServiceError:
        pass

