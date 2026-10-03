import re
from collections.abc import Iterator

from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from PIL import Image

from app.config import GEMINI_API_KEY, GEMINI_FALLBACK_MODELS, GEMINI_MODEL, GEMINI_THINKING
from app.errors import ServiceError

OCR_PROMPT = """You are an OCR engine. Extract the visible text from the image.
Rules: copy the text exactly; keep Devanagari matras and conjuncts precise; keep line breaks;
include both Hindi and English; do not translate, summarize, describe or invent anything;
return ONLY the text (no quotes, no markdown). If there is no readable text, return exactly:
NO_READABLE_TEXT"""

NO_TEXT = "NO_READABLE_TEXT"
_client: genai.Client | None = None
_no_thinking_cfg: set[str] = set()  # models that rejected the thinking setting


def _get_client() -> genai.Client:
    global _client
    if not GEMINI_API_KEY:
        raise ServiceError("The AI service is not configured correctly.", 503)
    if _client is None:
        _client = genai.Client(
            api_key=GEMINI_API_KEY,
            http_options=types.HttpOptions(timeout=60_000),  # milliseconds
        )
    return _client


def _config(model: str) -> types.GenerateContentConfig | None:
    # OCR needs no reasoning. Default "thinking" made a scan take ~17 s instead of ~2 s.
    if not GEMINI_THINKING or model in _no_thinking_cfg:
        return None
    return types.GenerateContentConfig(
        thinking_config=types.ThinkingConfig(thinking_level=GEMINI_THINKING)
    )


def _clean(text: str) -> str:
    text = (text or "").strip()
    text = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", text).strip()  # stray code fences
    if NO_TEXT in text and len(text) <= len(NO_TEXT) + 4:
        return ""
    return text


def _map_error(exc: Exception) -> ServiceError:
    code = getattr(exc, "code", None)
    raw = str(exc).lower()
    if "timed out" in raw or "timeout" in raw:
        return ServiceError("The request took too long. Please try again.", 504)
    if code == 429 or "quota" in raw or "resource_exhausted" in raw:
        return ServiceError("The free API limit has been reached. Please try again later.", 429)
    if code in (400, 401, 403) and ("api key" in raw or "api_key" in raw or code != 400):
        return ServiceError("The AI service is not configured correctly.", 503)
    return ServiceError("The AI service is temporarily unavailable.", 502)


def _models() -> list[str]:
    return [GEMINI_MODEL] + [m for m in GEMINI_FALLBACK_MODELS if m != GEMINI_MODEL]


def stream_text(image: Image.Image) -> Iterator[str]:
    """Yield the recognised text in pieces as Gemini produces it.

    Falls back to the next model on 404 (retired) / 503 (overloaded), but only before
    any text has been yielded.
    """
    client = _get_client()
    last_error: Exception | None = None

    # A "busy" (503) reply is usually momentary: retry the fast model once before
    # falling back to a slower one.
    attempts = [m for model in _models() for m in ([model, model] if model == GEMINI_MODEL else [model])]
    for model in attempts:
        yielded = False
        buffer = ""
        try:
            for chunk in client.models.generate_content_stream(
                model=model, contents=[OCR_PROMPT, image], config=_config(model)
            ):
                piece = chunk.text or ""
                if not piece:
                    continue
                if yielded:
                    yield piece.replace("```", "")
                    continue
                # Hold back the first few characters: they might be the NO_READABLE_TEXT sentinel.
                buffer += piece
                if len(buffer.strip()) < len(NO_TEXT) + 2:
                    continue
                start = re.sub(r"^\s*```[a-zA-Z]*\n?", "", buffer)
                if start.strip().startswith(NO_TEXT):
                    return
                yielded = True
                yield start.replace("```", "")
            if not yielded and _clean(buffer):
                yield _clean(buffer)
            return
        except genai_errors.APIError as exc:
            code = getattr(exc, "code", None)
            if code == 400 and "thinking" in str(exc).lower() and model not in _no_thinking_cfg:
                _no_thinking_cfg.add(model)  # this model rejects the setting: retry without it
                yield from stream_text(image)
                return
            if code in (404, 503) and not yielded:
                last_error = exc
                continue
            raise _map_error(exc) from exc
        except ServiceError:
            raise
        except Exception as exc:
            raise _map_error(exc) from exc

    if getattr(last_error, "code", None) == 503:
        raise ServiceError(
            "The AI service is busy right now. Please try again in a minute.", 503
        ) from last_error
    raise ServiceError(
        "No Gemini model is available. Set GEMINI_MODEL in backend/.env to a current model ID.",
        503,
    ) from last_error


def warm_up() -> None:
    """Create the client and open the HTTPS connection (metadata call, no generation)."""
    try:
        _get_client().models.get(model=GEMINI_MODEL)
    except Exception:
        pass


def extract_text(image: Image.Image) -> str:
    return _clean("".join(stream_text(image)))
