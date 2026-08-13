#!/usr/bin/env python3
"""Знак Point: рисуется кодом, одинаково на компьютере и на телефоне.

Знак у Point один — светящийся портал. Но жил он в трёх несогласованных копиях:
картинка для установщика, отрисовка окна и набор PNG для лаунчера телефона. Копии
разъезжались молча: в `point.ico` портал занимал сорок процентов ширины и в панели
задач превращался в точку, а переход цвета был растянут на весь кадр, отчего
макушка кольца выходила сиреневой вместо белой.

Здесь один источник формы. Доли радиуса общие, свет кладётся слоями: широкий
ореол, свечение кольца и только потом чёткая линия. Каждый размер рисуется
заново, а не ужимается из большого, — на 16 пикселях тонкое кольцо исчезает,
поэтому мелким оно даётся толще, а ореол убирается.

    python tools/make-icon.py

Пишет иконку компьютера (`desktop/src/main/resources`) и передний план иконки
телефона (`app/src/main/res/mipmap-*`). Отрисовка окна живёт в `pointGlyph`
(`Main.kt`) и держится тех же долей — меняются здесь, переносятся туда.
"""

import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
DESKTOP = os.path.join(HERE, "..", "desktop", "src", "main", "resources")
ANDROID = os.path.join(HERE, "..", "app", "src", "main", "res")
DOCS = os.path.join(HERE, "..", "docs")

TILE_IN = (0x14, 0x10, 0x21)
TILE_OUT = (0x08, 0x08, 0x0E)
HALO = (0x7B, 0x5C, 0xFF)
RING = [(0.00, (0xEA, 0xF0, 0xFF)), (0.45, (0x9B, 0x7B, 0xFF)), (1.00, (0x00, 0xA6, 0xFF))]

# Доли половины плашки: внешний край кольца и толщина линии.
RING_R = 0.583
RING_W = 0.257
HALO_R = 0.681
HALO_W = 0.34

ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]

# Плотности телефона: сторона иконки в пикселях при 108 dp.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Лаунчер обрезает иконку до 72 dp из 108 — рисовать надо в этой доле, иначе
# кольцо уедет под обрез.
SAFE = 72 / 108


def vertical_gradient(n, stops, span):
    """Столбик цвета: светлое кверху, фиолетовое, синее книзу.

    `span` — участок кадра, на который растянут переход. Растянутый на весь кадр,
    он тратит белое на пустоту над кольцом, и макушка выходит сиреневой.
    """
    top, bottom = span
    ys = np.clip((np.arange(n) - top) / max(1.0, bottom - top), 0.0, 1.0)
    pos = np.array([p for p, _ in stops])
    out = np.zeros((n, 3))
    for c in range(3):
        out[:, c] = np.interp(ys, pos, [col[c] for _, col in stops])
    return Image.fromarray(np.repeat(out[:, None, :], n, axis=1).astype("uint8"), "RGB")


def radial_tile(n):
    """Плашка: к центру чуть теплее, к краю почти чёрная."""
    ax = np.linspace(-1.0, 1.0, n)
    d = np.clip(np.hypot(*np.meshgrid(ax, ax)), 0.0, 1.0)
    img = np.zeros((n, n, 3))
    for c in range(3):
        img[:, :, c] = TILE_IN[c] + (TILE_OUT[c] - TILE_IN[c]) * d
    return Image.fromarray(img.astype("uint8"), "RGB")


def ring(n, r, width, blur=0.0):
    """Кольцо как маска по внешнему краю: рисуется с запасом, оттого края мягкие."""
    m = Image.new("L", (n, n), 0)
    box = (n / 2 - r, n / 2 - r, n / 2 + r, n / 2 + r)
    ImageDraw.Draw(m).ellipse(box, outline=255, width=max(1, round(width)))
    return m.filter(ImageFilter.GaussianBlur(blur)) if blur > 0 else m


def draw(size, zone=1.0, tile=True, supersample=None):
    """Знак в кадре `size`.

    `zone` — какую долю кадра занимает плашка: на компьютере всю, на телефоне 72
    из 108. `tile` — рисовать ли саму плашку: у телефона она отдельным слоем.
    """
    scale = supersample or (16 if size <= 32 else 8 if size <= 64 else 4)
    n = size * scale
    half = n / 2 * zone

    # На 16–24 пикселях тонкое кольцо пропадает, а ореол превращается в грязь.
    small = size <= 24
    ring_w = RING_W * (1.22 if small else 1.0)

    icon = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    shape = Image.new("L", (n, n), 0)
    inset = (n - n * zone) / 2 + n * 0.02 * zone
    ImageDraw.Draw(shape).rounded_rectangle(
        (inset, inset, n - inset, n - inset), radius=n * 0.235 * zone, fill=255
    )
    if tile:
        icon.paste(radial_tile(n), (0, 0), shape)

    colour = vertical_gradient(
        n, RING, (n / 2 - half * RING_R, n / 2 + half * RING_R)
    ).convert("RGBA")

    def lit(mask, strength, tint=None):
        layer = Image.new("RGBA", (n, n), tint + (0,)) if tint else colour.copy()
        layer.putalpha(mask.point(lambda v: int(v * strength)))
        return layer

    # Свет ложится слоями снизу вверх: широкий ореол, свечение самого кольца и
    # только потом чёткая линия. Без этого порядка кольцо расплывается в бублик.
    if not small:
        icon = Image.alpha_composite(
            icon, lit(ring(n, half * HALO_R, half * HALO_W, blur=n * 0.045 * zone), 0.34, HALO)
        )

    body = ring(n, half * RING_R, half * ring_w, blur=n * (0.003 if small else 0.0015))
    if not small:
        icon = Image.alpha_composite(
            icon, lit(body.filter(ImageFilter.GaussianBlur(n * 0.018 * zone)), 0.42)
        )
    icon = Image.alpha_composite(icon, lit(body, 1.0))

    if tile:
        icon.putalpha(Image.composite(icon.getchannel("A"), Image.new("L", (n, n), 0), shape))
    return icon.resize((size, size), Image.LANCZOS)


def write_desktop():
    # Крупный кадр идёт основой: Pillow выбрасывает из набора всё, что больше него.
    frames = sorted((draw(s) for s in ICO_SIZES), key=lambda f: -f.size[0])
    ico = os.path.normpath(os.path.join(DESKTOP, "point.ico"))
    frames[0].save(ico, format="ICO", sizes=[(s, s) for s in ICO_SIZES], append_images=frames[1:])
    draw(512).save(os.path.normpath(os.path.join(DESKTOP, "point-icon.png")), format="PNG")
    return ico


def write_android():
    written = []
    for density, px in DENSITIES.items():
        # Передний план — только свет: тёмное поле даёт отдельный слой фона.
        art = draw(px, zone=SAFE, tile=False, supersample=8)
        path = os.path.normpath(
            os.path.join(ANDROID, f"mipmap-{density}", "ic_launcher_foreground.png")
        )
        art.save(path, format="PNG")
        written.append(path)
    # Витрина магазина: тот же знак, но со своим полем — там слоёв нет.
    store = os.path.normpath(os.path.join(DOCS, "store-icon.png"))
    draw(512).save(store, format="PNG")
    written.append(store)
    return written


if __name__ == "__main__":
    print("компьютер:", write_desktop())
    for p in write_android():
        print("телефон  :", p)
