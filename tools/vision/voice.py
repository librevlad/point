#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Замер расшифровки голосового: что модель услышала против того, что было сказано.

  python tools/vision/voice.py --frames <папка с .wav/.ogg + одноимённые .txt> [--degrade]

Рядом с каждой записью лежит `.txt` — что было сказано на самом деле. Без эталона замер
превращается в «звучит правдоподобно», а это не число.

`--degrade` сначала портит запись под мессенджер: моно, 16 кГц, opus 24 кбит/с. Студийная
дорожка меряет язык, испорченная — жизнь; смешивать их в одно число нельзя.
"""
import argparse
import base64
import difflib
import pathlib
import re
import subprocess
import sys
import time

import requests

ROOT = pathlib.Path(__file__).resolve().parents[2]
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PROMPT = (
    "Расшифруй эту аудиозапись ДОСЛОВНО на языке оригинала. "
    "Верни ТОЛЬКО текст сказанного, без пояснений, без перевода, без описания записи. "
    "Если речь неразборчива — напиши ??? на её месте."
)


def key(name="GEMINI_API_KEY"):
    for line in (ROOT / "local.properties").read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return None


def transcribe_groq(path, model="whisper-large-v3-turbo", language="uk"):
    """Whisper на Groq: отдельная ручка расшифровки, а не «модель, которой показали файл».

    User-Agent обязателен: без него Groq отвечает 403 — ловушка, из-за которой провайдер
    считался мёртвым (замер 04.08.2026)."""
    for attempt in range(3):
        r = requests.post(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            headers={"Authorization": "Bearer " + key("GROQ_API_KEY"), "User-Agent": "Point/0.2"},
            files={"file": (path.name, path.read_bytes(), "audio/ogg")},
            data={"model": model, "language": language, "response_format": "json"}, timeout=180)
        if r.status_code in (429, 500, 502, 503):
            time.sleep(5 * (attempt + 1))
            continue
        if r.status_code != 200:
            return None, "HTTP %s: %s" % (r.status_code, r.text[:200])
        return r.json().get("text", "").strip(), None
    return None, "не дождались"


def transcribe(path, model="gemini-flash-latest"):
    mime = {"wav": "audio/wav", "ogg": "audio/ogg", "opus": "audio/ogg",
            "mp3": "audio/mpeg", "m4a": "audio/mp4"}.get(path.suffix.lstrip(".").lower(), "audio/ogg")
    url = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s" % (model, key())
    body = {
        "contents": [{"parts": [
            {"inline_data": {"mime_type": mime, "data": base64.b64encode(path.read_bytes()).decode("ascii")}},
            {"text": PROMPT},
        ]}],
        "generationConfig": {"temperature": 0},
    }
    for attempt in range(3):
        r = requests.post(url, json=body, timeout=180)
        if r.status_code in (429, 500, 502, 503):
            time.sleep(5 * (attempt + 1))
            continue
        if r.status_code != 200:
            return None, "HTTP %s: %s" % (r.status_code, r.text[:200])
        try:
            d = r.json()["candidates"][0]["content"]["parts"]
            return "".join(p.get("text", "") for p in d).strip(), None
        except Exception:
            return None, "непонятный ответ: %s" % r.text[:200]
    return None, "не дождались"


def words(s):
    """Слова без знаков и регистра: тире вместо дефиса и точка в конце — не ошибка слуха."""
    return [w for w in re.findall(r"\w+", s.lower().replace("ґ", "г").replace("'", ""), re.UNICODE)]


def wer(said, heard):
    """Доля слов, которые пришлось бы править человеку (классическая мера ошибки)."""
    a, b = words(said), words(heard)
    if not a:
        return None
    sm = difflib.SequenceMatcher(None, a, b)
    same = sum(bl.size for bl in sm.get_matching_blocks())
    return round(100.0 * (len(a) - same) / len(a), 1)


def to_messenger(src, dst):
    """Как звучит голосовуха из мессенджера: моно, 16 кГц, opus 24 кбит/с."""
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-ac", "1",
                        "-ar", "16000", "-c:a", "libopus", "-b:a", "24k", str(dst)],
                       capture_output=True)
    return dst if r.returncode == 0 and dst.exists() else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True)
    ap.add_argument("--degrade", action="store_true", help="сначала испортить под мессенджер")
    ap.add_argument("--model", default="whisper-large-v3-turbo")
    ap.add_argument("--engine", default="groq", choices=["groq", "gemini"])
    a = ap.parse_args()

    folder = pathlib.Path(a.frames)
    rows = []
    for audio in sorted(p for p in folder.iterdir() if p.suffix.lower() in (".wav", ".ogg", ".mp3", ".m4a", ".opus")):
        truth_file = audio.with_suffix(".txt")
        if not truth_file.exists():
            continue
        said = truth_file.read_text(encoding="utf-8").strip()
        target = audio
        if a.degrade:
            target = to_messenger(audio, folder / (audio.stem + ".msg.ogg")) or audio
        heard, err = (transcribe_groq(target, a.model) if a.engine == "groq"
                      else transcribe(target, a.model))
        e = wer(said, heard or "")
        rows.append((audio.name, e, err))
        print("--- %s%s" % (audio.name, " (под мессенджер)" if a.degrade and target != audio else ""))
        print("    сказано: %s" % said)
        print("    услышано: %s" % (heard or ("ОШИБКА: " + str(err))))
        print("    ошибка слов: %s" % ("—" if e is None else "%.1f%%" % e))
    good = [e for _, e, _ in rows if e is not None]
    if good:
        print("\nзаписей: %d · средняя ошибка слов: %.1f%% · дословно: %d" % (
            len(good), sum(good) / len(good), sum(1 for e in good if e == 0)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
