"""Download fonts and word lists used by the synthetic data generator.

    python prepare_data.py            # writes ml/data/fonts/*.ttf and ml/data/words_*.txt
"""
import re
import urllib.request
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"
FONT_BASE = "https://raw.githubusercontent.com/google/fonts/main/ofl/"
FONTS = [  # all cover Devanagari; most also cover Latin (checked per word at render time)
    "hind/Hind-Regular.ttf",
    "hind/Hind-Bold.ttf",
    "mukta/Mukta-Regular.ttf",
    "mukta/Mukta-Bold.ttf",
    "poppins/Poppins-Regular.ttf",
    "poppins/Poppins-SemiBold.ttf",
    "martel/Martel-Regular.ttf",
    "tirodevanagarihindi/TiroDevanagariHindi-Regular.ttf",
    "rozhaone/RozhaOne-Regular.ttf",
    "notosansdevanagari/NotoSansDevanagari%5Bwdth,wght%5D.ttf",
    "kalam/Kalam-Regular.ttf",
    "amita/Amita-Regular.ttf",
    "teko/Teko%5Bwght%5D.ttf",
    "laila/Laila-Regular.ttf",
]
WORDS = {
    "hi": "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/hi/hi_full.txt",
    "en": "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt",
}
SCRIPT = {"hi": re.compile(r"^[ऀ-ॿ]+$"), "en": re.compile(r"^[A-Za-z]+$")}


def fetch(url: str, dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 0:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    print("downloading", url)
    with urllib.request.urlopen(url, timeout=60) as r:
        dest.write_bytes(r.read())


def main(limit: int = 50_000) -> None:
    for f in FONTS:
        name = f.split("/")[-1].replace("%5B", "[").replace("%5D", "]")
        fetch(FONT_BASE + f, DATA / "fonts" / name)

    for lang, url in WORDS.items():
        raw = DATA / f"raw_{lang}.txt"
        fetch(url, raw)
        words = []
        for line in raw.read_text(encoding="utf-8").splitlines():
            word = line.split(" ")[0].strip()
            if SCRIPT[lang].match(word) and 1 < len(word) <= 20:
                words.append(word)
            if len(words) >= limit:
                break
        (DATA / f"words_{lang}.txt").write_text("\n".join(words), encoding="utf-8")
        print(lang, len(words), "words")


if __name__ == "__main__":
    main()
