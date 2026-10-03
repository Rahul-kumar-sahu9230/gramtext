"""SQLite store for field-pilot impact metrics.

Privacy: no images and no scanned text are stored - only counts, timings, engine
and the user's feedback. A corrected text is saved only when the user chooses to
send it (it helps build real training data).
"""
import sqlite3
import threading
import time
from contextlib import contextmanager

from app.config import DB_PATH

_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS scans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    engine TEXT,
    confidence REAL,
    latency_ms INTEGER,
    chars INTEGER,
    language TEXT,
    success INTEGER,
    client TEXT
);
CREATE TABLE IF NOT EXISTS tts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    chars INTEGER,
    latency_ms INTEGER,
    success INTEGER
);
CREATE TABLE IF NOT EXISTS feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    scan_id INTEGER,
    helpful INTEGER NOT NULL,
    corrected_text TEXT,
    comment TEXT,
    client TEXT
);
"""


@contextmanager
def _conn():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with _lock:
        conn = sqlite3.connect(DB_PATH)
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()


def init() -> None:
    with _conn() as c:
        c.executescript(SCHEMA)


def log_scan(engine, confidence, latency_ms, chars, language, success, client) -> int:
    with _conn() as c:
        cur = c.execute(
            "INSERT INTO scans (ts, engine, confidence, latency_ms, chars, language, success, client)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (time.time(), engine, confidence, latency_ms, chars, language, int(success), client),
        )
        return cur.lastrowid


def log_tts(chars, latency_ms, success) -> None:
    with _conn() as c:
        c.execute("INSERT INTO tts (ts, chars, latency_ms, success) VALUES (?, ?, ?, ?)",
                  (time.time(), chars, latency_ms, int(success)))


def add_feedback(scan_id, helpful, corrected_text, comment, client) -> int:
    with _conn() as c:
        cur = c.execute(
            "INSERT INTO feedback (ts, scan_id, helpful, corrected_text, comment, client)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (time.time(), scan_id, int(helpful), corrected_text, comment, client),
        )
        return cur.lastrowid


def metrics() -> dict:
    with _conn() as c:
        q = lambda sql: c.execute(sql).fetchone()
        scans, ok, avg_ms = q("SELECT COUNT(*), SUM(success), AVG(latency_ms) FROM scans")
        engines = dict(c.execute("SELECT engine, COUNT(*) FROM scans WHERE success = 1 GROUP BY engine").fetchall())
        langs = dict(c.execute("SELECT language, COUNT(*) FROM scans WHERE success = 1 GROUP BY language").fetchall())
        avg_conf = q("SELECT AVG(confidence) FROM scans WHERE engine = 'str'")[0]
        tts_n, tts_ok, tts_ms = q("SELECT COUNT(*), SUM(success), AVG(latency_ms) FROM tts")
        fb_n, fb_yes = q("SELECT COUNT(*), SUM(helpful) FROM feedback")
        corrections = q("SELECT COUNT(*) FROM feedback WHERE corrected_text IS NOT NULL AND corrected_text != ''")[0]
        users = q("SELECT COUNT(DISTINCT client) FROM scans WHERE client IS NOT NULL")[0]
        daily = c.execute(
            "SELECT date(ts, 'unixepoch', 'localtime') d, COUNT(*) FROM scans GROUP BY d ORDER BY d DESC LIMIT 30"
        ).fetchall()

    return {
        "scans": {"total": scans, "successful": ok or 0,
                  "avg_latency_ms": round(avg_ms) if avg_ms else None,
                  "by_engine": engines, "by_language": langs,
                  "str_avg_confidence": round(avg_conf, 3) if avg_conf else None},
        "tts": {"total": tts_n, "successful": tts_ok or 0, "avg_latency_ms": round(tts_ms) if tts_ms else None},
        "feedback": {"total": fb_n, "helpful": fb_yes or 0,
                     "helpful_rate": round((fb_yes or 0) / fb_n, 3) if fb_n else None,
                     "corrections_submitted": corrections},
        "unique_devices": users,
        "scans_per_day": [{"date": d, "scans": n} for d, n in daily],
    }
