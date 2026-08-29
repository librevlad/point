#!/usr/bin/env python3
"""Вес страницы PDF на кадрах корпуса (#1047).

Числа в KDoc `executors/.../PdfSheet.kt` и в проверке `PdfIsWorthSendingTest` сняты этим
скриптом. Он существует ровно затем, чтобы их можно было перемерить, а не поверить на слово:
кадры корпуса лежат вне репозитория (`tools/corpus/frames.tsv` — карта), и без инструмента
диапазон в комментарии никем не проверяется.

Повторяется то, что делает код, и на том размере, который достаётся человеку:

  * `Bitmaps.decodeUpright` — `sampleSizeFor(w, h, PROCESS_MAX_PX=1600)`, снимок 12 Мп
    раскодируется в 1500x2000;
  * цветная страница — ужатие до `pageMaxPx()` (A4, 150 dpi, 1754 px по длинной стороне) и
    `fewerTones` (ступень 1/32 канала);
  * чёрно-белая страница — та же бинаризация, что у `OpenCvScan.binarise`: CLAHE 2.0 (8x8) и
    adaptiveThreshold(GAUSSIAN, blockSize=15, C=10);
  * вес — deflate сырых RGB-байт: картинка внутри PDF лежит именно таким потоком.

Чего скрипт НЕ делает: не ищет страницу на кадре (`detectDocument`) и не выпрямляет её.
Чёрно-белые числа поэтому сняты с кадра целиком — настоящая страница вырезана из него, и
пикселей в ней меньше. Вывод «ужимать нечем» держится на числе НА МЕГАПИКСЕЛЬ и на том,
насколько ужатие меняет вес, — обе величины от вырезки не зависят.

    python tools/pdf-page-weight.py <кадр.jpg> [ещё кадры...]
    python tools/pdf-page-weight.py <каталог с кадрами>

Нужны numpy, pillow и opencv-python — тот же фильтр, что и на телефоне.
"""

import glob
import os
import sys
import zlib

import cv2
import numpy as np
from PIL import Image, ImageOps

# Те же числа, что в коде: Bitmaps.PROCESS_MAX_PX, PdfSheet.A4, PRINT_DPI, POINTS_PER_INCH, TONES.
PROCESS_MAX_PX = 1600
A4 = (595, 842)
PRINT_DPI = 150
POINTS_PER_INCH = 72
TONES = 32


def sample_size_for(width, height, max_px):
    """`com.point.core.flow.sampleSizeFor` — деление пополам, как у BitmapFactory."""
    sample, long_edge = 1, max(width, height)
    while long_edge // 2 >= max_px:
        long_edge //= 2
        sample *= 2
    return sample


def decode_upright(path):
    """Что отдаёт `Bitmaps.decodeUpright`: поворот по EXIF и предел длинной стороны."""
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    width, height = image.size
    sample = sample_size_for(width, height, PROCESS_MAX_PX)
    if sample > 1:
        image = image.resize((width // sample, height // sample), Image.BOX)
    return np.asarray(image, dtype=np.uint8)


def page_max_px():
    """`Sheet.pageMaxPx()` — длинная сторона листа в пикселях печатной чёткости."""
    return max(A4) * PRINT_DPI // POINTS_PER_INCH


def deflated(pixels):
    """Столько байт займёт страница в PDF: картинка лежит там потоком deflate."""
    return len(zlib.compress(np.ascontiguousarray(pixels).tobytes(), 6))


def shrunk(pixels, max_px):
    """`Bitmap.createScaledBitmap` до предела листа. Усреднение площадью — оценка снизу:
    билинейное сглаживание Android даёт страницу легче, то есть выигрыш чуть больше."""
    height, width = pixels.shape[:2]
    long_edge = max(width, height)
    if long_edge <= max_px:
        return pixels
    to_width = max(1, width * max_px // long_edge)
    to_height = max(1, height * max_px // long_edge)
    return np.asarray(Image.fromarray(pixels).resize((to_width, to_height), Image.BOX), dtype=np.uint8)


def fewer_tones(pixels):
    """`PdfSheet.fewerTones` — ступень в 1/32 канала."""
    channels = pixels.astype(np.int32)
    level = (channels * (TONES - 1) + 255 // 2) // 255
    return ((level * 255 + (TONES - 1) // 2) // (TONES - 1)).astype(np.uint8)


def binarised(pixels):
    """`OpenCvScan.binarise` — тем же фильтром, что и на телефоне."""
    gray = cv2.cvtColor(pixels, cv2.COLOR_RGB2GRAY)
    gray = cv2.createCLAHE(2.0, (8, 8)).apply(gray)
    black_white = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 15, 10
    )
    return np.dstack([black_white] * 3)


def frames_from(arguments):
    found = []
    for argument in arguments:
        if os.path.isdir(argument):
            found += sorted(glob.glob(os.path.join(argument, "*.jpg")))
            found += sorted(glob.glob(os.path.join(argument, "*.jpeg")))
            found += sorted(glob.glob(os.path.join(argument, "*.png")))
        else:
            found.append(argument)
    return found


def main(arguments):
    frames = frames_from(arguments)
    if not frames:
        print(__doc__)
        return 1

    print("кадр\tразмер\tбыло\tстало\tраз\tч/б\tч/б на Мп\tч/б после ужатия")
    ratios, ink_per_megapixel, ink_shrink = [], [], []
    for path in frames:
        pixels = decode_upright(path)
        height, width = pixels.shape[:2]
        megapixels = width * height / 1_000_000

        was = deflated(pixels)
        now = deflated(fewer_tones(shrunk(pixels, page_max_px())))
        ratios.append(was / now)

        ink = binarised(pixels)
        ink_was = deflated(ink)
        ink_now = deflated(shrunk(ink, page_max_px()))
        ink_per_megapixel.append(ink_was / 1024 / megapixels)
        ink_shrink.append(100.0 * (ink_now - ink_was) / ink_was)

        print(
            "%s\t%dx%d\t%.2f МБ\t%.2f МБ\t%.2f\t%.0f КБ\t%.0f КБ\t%+.0f %%"
            % (
                os.path.basename(path), width, height,
                was / 1024 / 1024, now / 1024 / 1024, was / now,
                ink_was / 1024, ink_per_megapixel[-1], ink_shrink[-1],
            )
        )

    print("")
    print("цветная страница легче в %.2f–%.2f раза" % (min(ratios), max(ratios)))
    print("чёрно-белая страница — %.0f–%.0f КБ на мегапиксель" % (min(ink_per_megapixel), max(ink_per_megapixel)))
    print("ужатие чёрно-белой до листа — %+.0f %%…%+.0f %%" % (min(ink_shrink), max(ink_shrink)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
