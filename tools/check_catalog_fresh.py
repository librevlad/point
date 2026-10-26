#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Каталог сервисов стареет вслух (#433).

У каждого сервиса в `AI_PROVIDERS` записано, когда проверяли его бесплатность (`checkedAt`) и
его обещание про присланное (`promiseCheckedAt`). Записано руками и забывается так же: сервис
снимает бесплатный уровень или меняет условия, а Point продолжает обещать человеку прежнее.
Тем же сроком стареет замер читателей страницы (`ReaderMeasure.measuredAt`): по нему стоит их
очередь, и замер полугодовой давности ставит первым того, кто давно читает хуже.

Здесь дата сверяется с сегодняшним днём. Запись старше срока роняет прогон и называется
поимённо. Это не проверка кода, а напоминание людям, поэтому идёт на main и раз в неделю,
а не на каждом PR, как и сверка сервера (`check_server_deployed.py`).

  python tools/check_catalog_fresh.py              # каталог против сегодняшнего дня
  python tools/check_catalog_fresh.py 2026-12-01   # против названного дня
"""
from __future__ import annotations

import calendar
import datetime
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "core/flow/src/main/kotlin/com/point/core/flow/AiProviders.kt")
MEASURES = os.path.join(ROOT, "core/flow/src/main/kotlin/com/point/core/flow/ReaderMeasures.kt")

# Сколько живёт проверка. Бесплатные уровни меняются раз в несколько месяцев; квартал —
# достаточно часто, чтобы не обещать устаревшего, и достаточно редко, чтобы не привыкнуть.
MAX_AGE_DAYS = 90

FIELDS = ("checkedAt", "promiseCheckedAt", "measuredAt")

# Запись каталога → поле, которым она себя называет.
RECORDS = {"AiProvider(": "name", "ReaderMeasure(": "reader"}


def parse_day(text: str) -> datetime.date:
    """`14.08.2026` — тот день; `08.2026` — последний день месяца: в нём проверка точно была."""
    parts = [int(p) for p in text.split(".")]
    if len(parts) == 3:
        return datetime.date(parts[2], parts[1], parts[0])
    if len(parts) == 2:
        month, year = parts
        return datetime.date(year, month, calendar.monthrange(year, month)[1])
    raise ValueError(f"не дата: {text!r}")


def entries(source: str) -> list[tuple[str, str, str]]:
    """(сервис, поле, дата) по каждой записи каталога."""
    found = []
    # Запись кончается там, где начинается следующая любого рода: иначе дата замера читателя
    # приписалась бы последнему сервису каталога.
    parts = re.split("(" + "|".join(map(re.escape, RECORDS)) + ")", source)
    for record, block in zip(parts[1::2], parts[2::2]):
        name = re.search(rf'\b{RECORDS[record]}\s*=\s*"([^"]+)"', block)
        if not name:
            continue
        for field in FIELDS:
            for date in re.findall(rf'\b{field}\s*=\s*"([^"]+)"', block):
                found.append((name.group(1), field, date))
    return found


def stale(source: str, today: datetime.date) -> list[str]:
    old = []
    for service, field, date in entries(source):
        age = (today - parse_day(date)).days
        if age > MAX_AGE_DAYS:
            old.append(f"{service}: {field} = {date} ({age} дн.)")
    return old


def main(argv: list[str]) -> int:
    today = datetime.date.fromisoformat(argv[1]) if len(argv) > 1 else datetime.date.today()
    source = ""
    for path in (CATALOG, MEASURES):
        with open(path, encoding="utf-8") as f:
            source += f.read()
    if not entries(source):
        print("в каталоге не нашлось ни одной даты — проверка ослепла")
        return 1
    old = stale(source, today)
    if old:
        print(f"Проверки старше {MAX_AGE_DAYS} дней — перепроверить бесплатность и обещание:")
        for line in old:
            print(f"  {line}")
        return 1
    print(f"Каталог свежий: все проверки моложе {MAX_AGE_DAYS} дней")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
