"""Каталог сервисов стареет вслух (#433).

Сверка живёт в `tools/`, но проверяется здесь, как и сверка сервера: другого набора тестов
на Python, который гоняет CI, в проекте нет.
"""
from __future__ import annotations

import datetime
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "tools"))

import check_catalog_fresh as check  # noqa: E402

CATALOG = """
    AiProvider(
        id = "groq",
        name = "Groq",
        checkedAt = "09.2026",
        promiseCheckedAt = "14.08.2026",
    ),
    AiProvider(
        name = "OpenAI",
        promiseCheckedAt = "01.06.2026",
    ),
"""


def test_month_counts_to_its_last_day():
    assert check.parse_day("09.2026") == datetime.date(2026, 9, 30)
    assert check.parse_day("14.08.2026") == datetime.date(2026, 8, 14)


def test_every_date_is_found_with_its_service():
    assert check.entries(CATALOG) == [
        ("Groq", "checkedAt", "09.2026"),
        ("Groq", "promiseCheckedAt", "14.08.2026"),
        ("OpenAI", "promiseCheckedAt", "01.06.2026"),
    ]


def test_old_check_is_named_and_fresh_one_is_not():
    old = check.stale(CATALOG, datetime.date(2026, 10, 23))
    assert len(old) == 1
    assert old[0].startswith("OpenAI: promiseCheckedAt")


def test_real_catalog_has_dates():
    source = pathlib.Path(check.CATALOG).read_text(encoding="utf-8")
    assert len(check.entries(source)) >= 10


MEASURES = """
    ReaderMeasure(
        reader = "ocr-space",
        read = 120,
        of = 300,
        medianMs = 4200,
        measuredAt = "01.06.2026",
    ),
"""


def test_reader_measure_ages_like_the_catalog():
    assert check.entries(MEASURES) == [("ocr-space", "measuredAt", "01.06.2026")]
    old = check.stale(CATALOG + MEASURES, datetime.date(2026, 10, 23))
    assert [line.split(":")[0] for line in old] == ["OpenAI", "ocr-space"]
