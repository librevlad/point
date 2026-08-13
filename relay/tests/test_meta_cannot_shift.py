"""Описание присланного нельзя сдвинуть содержимым (#927).

Имя присланного пишет чужой человек: он кладёт файл в ящик приёма по ссылке, и никакого
пропуска у него нет. Пока имя и тип лежали двумя строками, одинокий возврат каретки в имени
превращался при чтении в перевод строки — поля съезжали, и хвост имени становился типом.
Хозяин видел «визитка.vcf», а Point считал присланное веб-страницей.

Сторож стоит на классе: не на `\\r`, а на том, что ни один знак в имени не имеет права
менять соседнее поле.
"""
from __future__ import annotations

import json
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from point_server import mailbox  # noqa: E402

SHIFTY = [
    "визитка.vcf\rtext/html",
    "визитка.vcf\ntext/html",
    "визитка.vcf\r\ntext/html",
    "визитка.vcf text/html",
    "визитка.vcf\x00text/html",
    "визитка.vcf\ttext/html",
]


@pytest.mark.parametrize("name", SHIFTY)
def test_name_cannot_become_the_type(tmp_path, name):
    meta = str(tmp_path / "meta")
    mailbox.write_meta(meta, name, "text/vcard; charset=utf-8")

    got_name, got_mime = mailbox.read_meta(meta)

    assert got_mime == "text/vcard; charset=utf-8", "чужой человек подменил тип"
    assert "text/html" not in got_mime
    assert got_name, "имя пропало вовсе"


def test_type_cannot_become_the_name(tmp_path):
    meta = str(tmp_path / "meta")
    mailbox.write_meta(meta, "визитка.vcf", "text/plain\rФальшивое имя.exe")

    name, _ = mailbox.read_meta(meta)

    assert name == "визитка.vcf"


def test_ordinary_name_survives(tmp_path):
    meta = str(tmp_path / "meta")
    mailbox.write_meta(meta, "Счёт №4417 (копия).pdf", "application/pdf")

    assert mailbox.read_meta(meta) == ("Счёт №4417 (копия).pdf", "application/pdf")


def test_old_two_line_records_still_read(tmp_path):
    """На боевом сервере такие записи лежат прямо сейчас — присланное не должно пропасть."""
    meta = tmp_path / "meta"
    meta.write_text("Счёт 4417.pdf\napplication/pdf", encoding="utf-8")

    assert mailbox.read_meta(str(meta)) == ("Счёт 4417.pdf", "application/pdf")


def test_missing_and_empty_records_are_harmless(tmp_path):
    assert mailbox.read_meta(str(tmp_path / "нет")) == ("file", mailbox.DEFAULT_MIME)

    empty = tmp_path / "empty"
    empty.write_text("", encoding="utf-8")
    assert mailbox.read_meta(str(empty)) == ("file", mailbox.DEFAULT_MIME)


def test_written_record_is_one_line(tmp_path):
    """Одна строка — значит сдвигать нечего: перевод строки больше ничего не разделяет."""
    meta = tmp_path / "meta"
    mailbox.write_meta(str(meta), "имя\rвторое", "application/pdf")

    raw = meta.read_text(encoding="utf-8")
    assert "\n" not in raw and "\r" not in raw
    assert json.loads(raw)["mime"] == "application/pdf"
