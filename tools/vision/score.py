#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Счёт прогона: сколько строк ведомости модель отдала БЕЗ единой ошибки.

  python tools/vision/score.py --run <каталог прогона> --truth <эталон.tsv>

Меряется не «похожесть», а то, что человеку придётся или не придётся перепроверять:

  строк найдено      — сколько строк вообще опознано;
  строк дословно     — сколько совпали ПОЛНОСТЬЮ (номер, табельный, фамилия, сумма);
  ячеек верно        — доля верных ячеек (мягче, показывает, насколько промах глубок);
  сумма разошлась    — сошёлся ли итог: это единственная проверка, которая ловит ошибку,
                       которую модель сделала уверенно.

Последнее — главное число. Уверенность модели врёт, арифметика нет.
"""
import argparse
import pathlib
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def cells(line):
    """Строка вывода → ячейки. Модели отдают либо markdown-таблицу, либо табуляцию."""
    s = line.strip()
    if not s:
        return []
    if s.startswith("|"):
        parts = [c.strip() for c in s.strip("|").split("|")]
    elif "\t" in s:
        parts = [c.strip() for c in s.split("\t")]
    else:
        return []
    return [c for c in parts if c not in ("", "---", ":---", "---:")] if set("".join(parts)) <= set("-:| ") is False else parts


def rows_of(text):
    """Строки таблицы из дословного вывода: номер, табельный, фамилия, сумма."""
    out = {}
    for line in text.splitlines():
        c = cells(line)
        if len(c) < 4:
            continue
        num = c[0].strip()
        if not re.fullmatch(r"\d{1,3}", num):
            continue
        out[int(num)] = [x.strip() for x in c[:4]]
    return out


def truth_of(path):
    out, total = {}, None
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        c = [x.strip() for x in line.split("\t")]
        if len(c) >= 4 and re.fullmatch(r"\d{1,3}", c[0]):
            out[int(c[0])] = c[:4]
        elif "РАЗОМ" in line or "РАЗОМ:" in line:
            total = c[-1]
    return out, total


def norm(v):
    """Пробелы и разделитель копеек — не ошибка чтения: 12 345,67 и 12345,67 это одно число."""
    v = v.replace(" ", " ").replace(" ", "").replace(" ", "")
    return v.replace(".", ",").strip()


def amount(v):
    v = norm(v).replace(",", ".")
    try:
        return float(v)
    except Exception:
        return None


def degenerated(text, times=6):
    """Модель зациклилась? Проверено на архивной рукописи 1767 года: Mistral OCR выдал одну
    выдуманную строку сорок раз подряд и НЕ сказал «не читаю».

    Это худший вид отказа — снаружи он выглядит как результат. Поймать его дёшево: настоящий
    документ не повторяет одну содержательную строку десятками. Дешевле, чем любая проверка
    уверенности, и честнее: уверенность модели здесь была высокой."""
    lines = [l.strip() for l in text.splitlines() if len(l.strip()) > 12]
    if not lines:
        return None
    best, run, prev = 1, 1, None
    for l in lines:
        run = run + 1 if l == prev else 1
        best, prev = max(best, run), l
    return None if best < times else "строка повторена %d раз подряд" % best


def score(run_dir, truth_path, suffix):
    truth, truth_total = truth_of(truth_path)
    print("%-22s %7s %7s %7s %9s  %s" % ("кадр", "найд.", "дословно", "ячеек", "итог", "провайдер/способ"))
    results = []
    for f in sorted(pathlib.Path(run_dir).rglob("*" + suffix)):
        text = f.read_text(encoding="utf-8", errors="replace")
        loop = degenerated(text)
        if loop:
            print("%-22s %s  ← ВЫРОЖДЕНИЕ: %s" % (f.name.split(".")[0], " " * 34, loop))
            continue
        got = rows_of(text)
        exact = sum(1 for n, r in truth.items() if n in got and [norm(x) for x in got[n]] == [norm(x) for x in r])
        cells_ok = cells_all = 0
        for n, r in truth.items():
            for i, want in enumerate(r):
                cells_all += 1
                if n in got and i < len(got[n]) and norm(got[n][i]) == norm(want):
                    cells_ok += 1
        # Итог: сумма прочитанных сумм против настоящего итога.
        got_total = sum(a for a in (amount(r[3]) for r in got.values()) if a is not None)
        want_total = amount(truth_total) if truth_total else None
        delta = None if want_total is None else round(got_total - want_total, 2)
        way = f.parent.name
        results.append((f.stem.replace(suffix, ""), len(got), exact, cells_ok, cells_all, delta, way))
        print("%-22s %7d %7d/%-2d %6.0f%% %9s  %s" % (
            f.name.split(".")[0], len(got), exact, len(truth),
            100.0 * cells_ok / max(1, cells_all),
            "сошёлся" if delta == 0 else ("%+.2f" % delta if delta is not None else "?"), way))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--suffix", default=".whole.txt")
    a = ap.parse_args()
    score(a.run, a.truth, a.suffix)
    return 0


if __name__ == "__main__":
    sys.exit(main())
