import re

DEVANAGARI = re.compile(r"[ऀ-ॿ]")


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def score(preds: list[str], labels: list[str]) -> dict:
    """Character accuracy (1 - CER) and word accuracy, overall and per script."""
    groups = {"all": [], "hi": [], "en": []}
    for p, t in zip(preds, labels):
        item = (p, t)
        groups["all"].append(item)
        groups["hi" if DEVANAGARI.search(t) else "en"].append(item)

    out = {}
    for name, items in groups.items():
        if not items:
            continue
        chars = sum(len(t) for _, t in items) or 1
        errors = sum(edit_distance(p, t) for p, t in items)
        out[name] = {
            "samples": len(items),
            "char_accuracy": round(max(0.0, 1 - errors / chars), 4),
            "word_accuracy": round(sum(p == t for p, t in items) / len(items), 4),
        }
    return out
