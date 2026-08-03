#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Прогон кадра через зрячую модель тремя способами (см. README.md рядом).

  python tools/vision/run.py --frames <папка> --way whole --provider gemini --out <каталог>

Кладёт дословный вывод модели. Ничего не подчищает: красивый отчёт по подчищенному выводу —
самообман, а не замер.
"""
import argparse
import base64
import io
import json
import os
import pathlib
import sys
import time

import requests
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]

# Консоль Windows по умолчанию не в UTF-8, и падение на печати итога съело бы весь прогон.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# --- промпты ------------------------------------------------------------------------------------
# Требование дословности стоит в каждом: модель охотно «улучшает» документ, и это худшая ошибка —
# она не выглядит ошибкой.

WHOLE = (
    "Прочитай этот документ ДОСЛОВНО. Верни только текст документа, сохраняя строки и порядок. "
    "Таблицу отдавай строками, колонки разделяй символом табуляции. "
    "Ничего не исправляй, не дополняй и не переводи. "
    "Если фрагмент не читается — напиши на его месте ??? и продолжай."
)

LAYOUT = (
    "Ты — разметчик документа. Найди на изображении смысловые области и верни СТРОГО JSON-массив "
    "без пояснений. Каждый элемент: "
    '{"box_2d": [ymin, xmin, ymax, xmax], "type": "<тип>", "hint": "<что там за содержимое>"}. '
    "Координаты — целые 0..1000 относительно размера изображения. "
    "Типы: table_row (строка таблицы), cell (отдельная ячейка), header (шапка), field (поле формы), "
    "text (абзац), signature (подпись/печать), other. "
    "Для таблиц выделяй КАЖДУЮ строку отдельным элементом сверху вниз. "
    "hint пиши по-русски: например «фамилия и сумма», «число», «дата»."
)

# Узкий вопрос по фрагменту — та самая комбинация, ради которой всё затевается: на «прочитай эту
# клетку» модель почти не ошибается, на «прочитай весь лист» ошибается предсказуемо.
PART = (
    "Это фрагмент документа: {hint}. Прочитай его ДОСЛОВНО и верни ТОЛЬКО содержимое фрагмента, "
    "без пояснений и без кавычек. Колонки внутри фрагмента разделяй табуляцией. "
    "Ничего не исправляй и не дополняй. Нечитаемое место обозначь ???."
)


def load_key(env_name):
    """Ключ — из окружения, иначе из local.properties (там он и живёт у владельца)."""
    if os.environ.get(env_name):
        return os.environ[env_name]
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.strip().startswith(env_name + "="):
                return line.split("=", 1)[1].strip()
    return None


def providers():
    return json.loads((pathlib.Path(__file__).parent / "providers.json").read_text(encoding="utf-8"))["providers"]


def ask(provider, model, image_bytes, prompt, retries=3):
    """Один вопрос модели про одну картинку. Возвращает (текст, ошибка)."""
    cfg = providers()[provider]
    key = load_key(cfg["key_env"])
    if not key:
        return None, "нет ключа (%s)" % cfg["key_env"]
    b64 = base64.b64encode(image_bytes).decode("ascii")

    if cfg["kind"] == "mistral-ocr":
        # Специальная OCR-ручка промпта не принимает: она не «отвечает на вопрос», а разбирает
        # страницу и отдаёт разметку. Поэтому для неё способ «по клеткам» — это тот же вызов на
        # каждом фрагменте, а не другой промпт.
        url, headers = cfg["url"], {"Authorization": "Bearer " + key}
        body = {
            "model": model,
            "document": {"type": "image_url", "image_url": "data:image/jpeg;base64," + b64},
        }
    elif cfg["kind"] == "google":
        url = cfg["url"].format(model=model) + "?key=" + key
        body = {
            "contents": [{"parts": [
                {"inline_data": {"mime_type": "image/jpeg", "data": b64}},
                {"text": prompt},
            ]}],
            # Дословность — это температура 0: «творческий» пересказ документа нам вреден.
            "generationConfig": {"temperature": 0},
        }
        headers = {}
    else:  # openai-совместимый
        url = cfg["url"]
        body = {
            "model": model,
            "temperature": 0,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}},
            ]}],
        }
        headers = {"Authorization": "Bearer " + key}

    for attempt in range(retries):
        try:
            r = requests.post(url, json=body, headers=headers, timeout=180)
        except Exception as e:
            return None, "сеть: %s" % e
        if r.status_code == 429 or r.status_code >= 500:
            # Квота кончилась или сервер лёг — ждём и пробуем ещё. Покупать нельзя (решение владельца).
            time.sleep(5 * (attempt + 1))
            continue
        if r.status_code != 200:
            return None, "HTTP %s: %s" % (r.status_code, r.text[:300])
        data = r.json()
        try:
            if cfg["kind"] == "mistral-ocr":
                return "\n".join(p.get("markdown", "") for p in data.get("pages", [])), None
            if cfg["kind"] == "google":
                return "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"]), None
            return data["choices"][0]["message"]["content"], None
        except Exception:
            return None, "непонятный ответ: %s" % json.dumps(data)[:300]
    return None, "не дождались (429/5xx %d раз)" % retries


def as_jpeg(path, max_side=1600):
    """Кадр в JPEG разумного размера: чужие пределы на размер запроса — тоже часть замера."""
    img = Image.open(path).convert("RGB")
    if max(img.size) > max_side:
        k = max_side / max(img.size)
        img = img.resize((int(img.width * k), int(img.height * k)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=92)
    return buf.getvalue(), img


def crop(img, box, pad=0.01):
    """box = [ymin, xmin, ymax, xmax] в 0..1000. Поля добавляются: модели-разметчику свойственно
    резать по самому краю букв, и без запаса читатель теряет хвосты."""
    y0, x0, y1, x1 = [v / 1000.0 for v in box]
    y0, x0 = max(0.0, y0 - pad), max(0.0, x0 - pad)
    y1, x1 = min(1.0, y1 + pad), min(1.0, x1 + pad)
    if y1 <= y0 or x1 <= x0:
        return None
    piece = img.crop((int(x0 * img.width), int(y0 * img.height), int(x1 * img.width), int(y1 * img.height)))
    if min(piece.size) < 8:
        return None
    # Мелкий фрагмент увеличиваем: модель читает его как отдельную картинку, и разрешение здесь
    # решает больше, чем что-либо ещё.
    if max(piece.size) < 600:
        k = 600 / max(piece.size)
        piece = piece.resize((int(piece.width * k), int(piece.height * k)), Image.LANCZOS)
    buf = io.BytesIO()
    piece.save(buf, "JPEG", quality=92)
    return buf.getvalue()


def parse_regions(text):
    """Модель обещала строгий JSON. Обещание проверяем, а не надеемся."""
    if not text:
        return []
    s = text.strip()
    if s.startswith("```"):
        s = s.split("```")[1]
        s = s[4:] if s.lower().startswith("json") else s
    start, end = s.find("["), s.rfind("]")
    if start < 0 or end < 0:
        return []
    try:
        raw = json.loads(s[start:end + 1])
    except Exception:
        return []
    out = []
    for r in raw:
        box = r.get("box_2d") or r.get("box")
        if not (isinstance(box, list) and len(box) == 4):
            continue
        try:
            box = [int(v) for v in box]
        except Exception:
            continue
        out.append({"box_2d": box, "type": r.get("type", "other"), "hint": r.get("hint", "")})
    return out


def run_whole(frame, out, provider, model):
    blob, _ = as_jpeg(frame)
    text, err = ask(provider, model, blob, WHOLE)
    (out / (frame.stem + ".whole.txt")).write_text(text or ("ОШИБКА: " + str(err)), encoding="utf-8")
    return text, err


def run_parts(frame, out, provider, model, layout_model=None, layout_provider=None):
    # Разметчик и читатель — РАЗНЫЕ роли, и часто разные модели: специальная OCR-ручка отлично
    # читает, но координат не отдаёт вовсе. Разделение ролей и есть то, что здесь проверяется.
    blob, img = as_jpeg(frame)
    raw, err = ask(layout_provider or provider, layout_model or model, blob, LAYOUT)
    (out / (frame.stem + ".layout.raw.txt")).write_text(raw or ("ОШИБКА: " + str(err)), encoding="utf-8")
    regions = parse_regions(raw)
    (out / (frame.stem + ".layout.json")).write_text(json.dumps(regions, ensure_ascii=False, indent=1), encoding="utf-8")
    if not regions:
        return None, "разметчик не вернул областей"

    lines, log = [], []
    for i, r in enumerate(regions):
        piece = crop(img, r["box_2d"])
        if piece is None:
            log.append({"i": i, "skip": "пустая область", **r})
            continue
        text, e = ask(provider, model, piece, PART.format(hint=r.get("hint") or r.get("type")))
        log.append({"i": i, "type": r["type"], "hint": r["hint"], "text": text, "error": e})
        if text:
            lines.append(text.strip())
    (out / (frame.stem + ".parts.json")).write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")
    joined = "\n".join(lines)
    (out / (frame.stem + ".parts.txt")).write_text(joined, encoding="utf-8")
    return joined, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True, help="папка с кадрами (вне репозитория)")
    ap.add_argument("--way", default="whole", choices=["whole", "parts", "both"])
    ap.add_argument("--provider", default="gemini")
    ap.add_argument("--model", default=None)
    ap.add_argument("--layout-model", default=None)
    ap.add_argument("--layout-provider", default=None)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    cfg = providers()[a.provider]
    model = a.model or cfg["models"][0]
    frames = sorted(p for p in pathlib.Path(a.frames).iterdir()
                    if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"))
    if not frames:
        print("в папке нет кадров:", a.frames, file=sys.stderr)
        return 2
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    for frame in frames:
        for way in (["whole", "parts"] if a.way == "both" else [a.way]):
            t0 = time.time()
            if way == "whole":
                text, err = run_whole(frame, out, a.provider, model)
            else:
                lp = a.layout_provider
                lm = a.layout_model or (providers()[lp]["models"][0] if lp else None)
                text, err = run_parts(frame, out, a.provider, model, lm, lp)
            print("%-24s %-6s %s (%.1fс)" % (
                frame.name, way,
                ("ошибка: " + str(err)) if err else "%d символов" % len(text or ""),
                time.time() - t0), flush=True)
    print("готово →", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
