#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Геометрия из самого бланка: строки и колонки по линиям сетки, а не с глазомера модели.

  python tools/vision/grid.py <кадр.jpg> <куда> [--cells]

Сильная форма комбинации. Наивная (языковая модель размечает, читатель читает фрагменты) на
замере 04.08.2026 проиграла одиночному прогону 7/24 против 20/24: координаты «на глазок» режут
куски поперёк строк. Здесь координаты берутся из того, что на бланке нарисовано, — из линий.

Метод простой и потому надёжный: тёмность по строкам и по столбцам. Там, где нарисована линия,
тёмность резко выше соседей. Никакой модели, никакой сети, миллисекунды.

Ограничение названо честно: работает на бланках С ЛИНИЯМИ. Бланк без сетки требует другого
источника геометрии (специализированная модель разметки) — это следующий замер.
"""
import argparse
import pathlib
import sys

from PIL import Image, ImageOps

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def lines(profile, min_gap, strength=1.6):
    """Позиции линий: значения, заметно превышающие средний уровень, схлопнутые в одну на группу."""
    if not profile:
        return []
    avg = sum(profile) / len(profile)
    hot = [i for i, v in enumerate(profile) if v > avg * strength]
    out = []
    for i in hot:
        if not out or i - out[-1] > min_gap:
            out.append(i)
        else:
            out[-1] = i  # держим последнюю позицию группы: линия толще одного пикселя
    return out


def profiles(img):
    """Тёмность по строкам и по столбцам. Инверсия — чтобы «темнее» значило «больше»."""
    g = ImageOps.invert(ImageOps.grayscale(img))
    w, h = g.size
    px = g.load()
    rows = [sum(px[x, y] for x in range(0, w, 2)) for y in range(h)]
    cols = [sum(px[x, y] for y in range(0, h, 2)) for x in range(w)]
    return rows, cols


def find(img, min_row_gap=None, min_col_gap=None):
    rows, cols = profiles(img)
    h, w = img.height, img.width
    ys = lines(rows, min_row_gap or max(4, h // 80))
    xs = lines(cols, min_col_gap or max(8, w // 40))
    return ys, xs


def slices(img, ys, xs, cells=False, pad=2):
    """Куски: полосы-строки либо отдельные ячейки. Полоса надёжнее — в ней виден контекст строки,
    и читатель не путает колонку; ячейка точнее, но дороже в запросах."""
    out = []
    for i in range(len(ys) - 1):
        y0, y1 = ys[i] + pad, ys[i + 1] - pad
        if y1 - y0 < 6:
            continue
        if not cells or len(xs) < 2:
            out.append(("r%02d" % i, (0, y0, img.width, y1)))
            continue
        for j in range(len(xs) - 1):
            x0, x1 = xs[j] + pad, xs[j + 1] - pad
            if x1 - x0 < 6:
                continue
            out.append(("r%02dc%02d" % (i, j), (x0, y0, x1, y1)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frame")
    ap.add_argument("out")
    ap.add_argument("--cells", action="store_true", help="резать по ячейкам, а не по строкам")
    ap.add_argument("--scale", type=int, default=4, help="во сколько раз увеличить кусок")
    a = ap.parse_args()

    img = Image.open(a.frame).convert("RGB")
    ys, xs = find(img)
    pieces = slices(img, ys, xs, a.cells)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    stem = pathlib.Path(a.frame).stem
    for name, box in pieces:
        piece = img.crop(box)
        if a.scale > 1:
            piece = piece.resize((piece.width * a.scale, piece.height * a.scale), Image.LANCZOS)
        piece.save(out / ("%s__%s.jpg" % (stem, name)), "JPEG", quality=95)
    print("линий по горизонтали: %d, по вертикали: %d → кусков: %d" % (len(ys), len(xs), len(pieces)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
