#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Из чистого скана — те самые кадры, на которых Point ломался (#262).

  python tools/vision/degrade.py <чистый.jpg> <куда>

Смысл: эталон известен ТОЧНО (он же чистый кадр), а трудность добавлена управляемо. На настоящем
фото ни того, ни другого нет — там неизвестно ни что написано, ни насколько плохо снято, и любое
число получается спорным.

Четыре порчи, взятые из замера корпуса, а не из головы:
  angle  — бумага под углом (перспектива): половина текста терялась;
  shadow — тень от руки и неровный свет;
  dim    — съёмка в плохом свете: шум и низкий контраст;
  small  — снято издалека: мелкий текст, тот же кадр в четверть разрешения.
"""
import pathlib
import sys

from PIL import Image, ImageEnhance, ImageFilter


def angle(img, k=0.22):
    """Перспектива: верх уходит вдаль, как при съёмке лежащего листа с руки."""
    w, h = img.size
    dx = int(w * k)
    # PIL ждёт коэффициенты обратного преобразования — считаем их из четырёх пар точек.
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    dst = [(dx, 0), (w - dx // 2, int(h * 0.04)), (w, h), (0, int(h * 0.97))]
    return img.transform((w, h), Image.PERSPECTIVE, _coeffs(dst, src), Image.BICUBIC, fillcolor=(238, 236, 232))


def _coeffs(src, dst):
    import numpy
    m = []
    for (x, y), (u, v) in zip(src, dst):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    a = numpy.matrix(m, dtype=float)
    b = numpy.array(dst).reshape(8)
    return numpy.array(numpy.dot(numpy.linalg.pinv(a), b)).reshape(8)


def shadow(img):
    """Тень от руки: плавный градиент слева и мягкое пятно — самый частый брак съёмки."""
    w, h = img.size
    mask = Image.new("L", (w, h), 255)
    px = mask.load()
    for x in range(w):
        v = 255 if x > w * 0.55 else int(120 + 135 * (x / (w * 0.55)))
        for y in range(h):
            px[x, y] = v
    mask = mask.filter(ImageFilter.GaussianBlur(w // 25))
    dark = ImageEnhance.Brightness(img).enhance(0.45)
    return Image.composite(img, dark, mask)


def dim(img):
    """Плохой свет: контраст вниз, зерно вверх."""
    import random
    out = ImageEnhance.Contrast(ImageEnhance.Brightness(img).enhance(0.72)).enhance(0.65)
    px = out.load()
    random.seed(20260804)
    for _ in range((out.width * out.height) // 12):
        x, y = random.randrange(out.width), random.randrange(out.height)
        n = random.randint(-38, 38)
        r, g, b = px[x, y]
        px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)))
    return out.filter(ImageFilter.GaussianBlur(0.6))


def small(img):
    """Снято издалека: тот же кадр в четверть разрешения — мелкий текст, который и подводил."""
    return img.resize((img.width // 4, img.height // 4), Image.LANCZOS)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    img = Image.open(src).convert("RGB")
    for name, fn in (("angle", angle), ("shadow", shadow), ("dim", dim), ("small", small)):
        dst = out / ("%s_%s.jpg" % (src.stem, name))
        fn(img).save(dst, "JPEG", quality=88)
        print(dst.name, dst.stat().st_size // 1024, "КБ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
