#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ведомость с ТОЧНО известным содержимым — эталон для замера зрячих моделей.

  python tools/vision/make_sheet.py <куда> [строк]

Зачем синтетика, если есть настоящие документы: у настоящего кадра эталон приходится сочинять
глазами, и спор «модель ошиблась или мы не разобрали» не решается никогда. Здесь эталон известен
до съёмки — рядом кладётся `.tsv`, и любое расхождение это ошибка модели, а не мнение.

Настоящие документы владельца этим не заменяются: синтетика меряет устойчивость к порче кадра
(`degrade.py`), а не жизненное разнообразие бланков.
"""
import pathlib
import random
import sys

from PIL import Image, ImageDraw, ImageFont

FAMILIES = ["Іванченко", "Петренко", "Коваль", "Шевченко", "Бондаренко", "Ткаченко", "Мельник",
            "Кравчук", "Олійник", "Марченко", "Гуменюк", "Савченко", "Руденко", "Лисенко",
            "Поліщук", "Демченко", "Захарчук", "Кузьменко", "Мороз", "Дідух", "Гаврилюк",
            "Наконечний", "Стеценко", "Юрченко", "Панасюк", "Білаш", "Онищенко", "Романюк",
            "Чорний", "Сидоренко", "Гончар", "Верес"]
INITIALS = ["А.", "Б.", "В.", "Г.", "Д.", "Е.", "Ж.", "З.", "И.", "К.", "Л.", "М.", "Н.", "О.", "П."]


def font(size, bold=False):
    for name in (("arialbd.ttf", "arial.ttf") if bold else ("arial.ttf",)):
        p = pathlib.Path("C:/Windows/Fonts") / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


def build(rows=24, seed=20260804):
    rnd = random.Random(seed)
    data = []
    for i in range(1, rows + 1):
        name = "%s %s%s" % (rnd.choice(FAMILIES), rnd.choice(INITIALS), rnd.choice(INITIALS))
        # Суммы «как в жизни»: не круглые, с копейками — именно на них ломается распознавание.
        amount = "%d,%02d" % (rnd.randint(1800, 24500), rnd.randint(0, 99))
        tab = "%04d" % rnd.randint(1000, 9999)
        data.append((str(i), tab, name, amount))
    return data


def render(data, path, title="ВІДОМІСТЬ на виплату № 47/2 від 04.08.2026"):
    w, pad, head_h, row_h = 1240, 40, 120, 44
    h = head_h + row_h * (len(data) + 1) + pad * 2
    img = Image.new("RGB", (w, h), (252, 251, 248))
    d = ImageDraw.Draw(img)
    d.text((pad, pad), title, font=font(26, True), fill=(20, 20, 20))
    d.text((pad, pad + 38), "Підрозділ: господарча частина        Валюта: грн", font=font(19), fill=(60, 60, 60))

    cols = [(pad, 70, "№"), (pad + 70, 130, "Таб. №"), (pad + 200, 640, "Прізвище та ініціали"),
            (pad + 840, 240, "Сума"), (pad + 1080, 120, "Підпис")]
    y = pad + head_h
    d.rectangle([pad, y, w - pad, y + row_h], fill=(238, 238, 234))
    for x, cw, name in cols:
        d.text((x + 8, y + 12), name, font=font(19, True), fill=(20, 20, 20))
    y += row_h

    total = 0.0
    for i, (num, tab, name, amount) in enumerate(data):
        if i % 2:
            d.rectangle([pad, y, w - pad, y + row_h], fill=(246, 246, 243))
        for (x, cw, _), value in zip(cols, (num, tab, name, amount)):
            d.text((x + 8, y + 11), value, font=font(21), fill=(15, 15, 15))
        total += float(amount.replace(",", "."))
        y += row_h

    d.text((cols[2][0] + 8, y + 12), "РАЗОМ:", font=font(21, True), fill=(15, 15, 15))
    d.text((cols[3][0] + 8, y + 12), ("%.2f" % total).replace(".", ","), font=font(21, True), fill=(15, 15, 15))

    # Сетка: границы ячеек — это и есть то, что должен найти разметчик.
    top = pad + head_h
    for r in range(len(data) + 3):
        yy = top + row_h * r
        d.line([pad, yy, w - pad, yy], fill=(150, 150, 148), width=1)
    for x, cw, _ in cols:
        d.line([x, top, x, top + row_h * (len(data) + 2)], fill=(150, 150, 148), width=1)
    d.line([w - pad, top, w - pad, top + row_h * (len(data) + 2)], fill=(150, 150, 148), width=1)

    img.save(path, "JPEG", quality=95)
    return total


def main():
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    rows = int(sys.argv[2]) if len(sys.argv) > 2 else 24
    out.mkdir(parents=True, exist_ok=True)
    data = build(rows)
    total = render(data, out / "10_vedomost.jpg")
    # Эталон — машинный, тем же порядком колонок, что и в бланке.
    lines = ["№\tТаб. №\tПрізвище та ініціали\tСума"]
    lines += ["\t".join(r) for r in data]
    lines.append("\t\tРАЗОМ:\t" + ("%.2f" % total).replace(".", ","))
    (out / "10_vedomost.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("ведомость:", out / "10_vedomost.jpg", "| строк:", rows, "| итог:", ("%.2f" % total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
