"""Character set for the GramText STR model (Hindi/Devanagari + English).

Index 0 is the CTC blank. Text is NFC-normalised and recognised at Unicode
code-point level, so conjuncts and matras are learned as sequences.
"""
import unicodedata

DEVANAGARI = [
    chr(c) for c in range(0x0900, 0x0980) if unicodedata.category(chr(c)) != "Cn"
]
LATIN = list("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
PUNCT = list(" .,:;!?'\"()[]-/%&+*#@₹–")

CHARS = DEVANAGARI + LATIN + PUNCT
BLANK = 0
NUM_CLASSES = len(CHARS) + 1
CHAR_TO_IDX = {c: i + 1 for i, c in enumerate(CHARS)}

_REPLACE = {"’": "'", "‘": "'", "“": '"', "”": '"', "—": "–", "‌": "", "‍": ""}


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    for a, b in _REPLACE.items():
        text = text.replace(a, b)
    return " ".join(text.split())


def encode(text: str) -> list[int]:
    return [CHAR_TO_IDX[c] for c in normalize(text) if c in CHAR_TO_IDX]


def is_supported(text: str) -> bool:
    text = normalize(text)
    return bool(text) and all(c in CHAR_TO_IDX for c in text)


def decode(indices, probs=None) -> tuple[str, float]:
    """Greedy CTC decode. Returns (text, confidence = mean prob of emitted chars)."""
    out, confs, prev = [], [], BLANK
    for t, idx in enumerate(indices):
        idx = int(idx)
        if idx != BLANK and idx != prev:
            out.append(CHARS[idx - 1])
            if probs is not None:
                confs.append(float(probs[t]))
        prev = idx
    conf = sum(confs) / len(confs) if confs else 0.0
    return "".join(out), conf
