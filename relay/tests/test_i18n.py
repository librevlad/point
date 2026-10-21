"""Сервер говорит на языке человека: английский, украинский, русский (#1473).

Язык — из `Accept-Language`; не один из трёх или заголовка нет — английский. Каталог переводов
обязан покрывать каждую строку, которую сервер может показать: строка без перевода молча
осталась бы русской у англичанина.
"""
from __future__ import annotations

import ast
import pathlib
import re

import pytest

from point_server import i18n, mailbox, pages, upload_page
from point_server.i18n_texts import TEXTS

SERVER = pathlib.Path(__file__).resolve().parent.parent / "point_server"
CYR = re.compile("[А-Яа-яЁёІіЇїЄєҐґ]")


def _literal(node: ast.AST) -> str | None:
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None


def _keys() -> set[str]:
    """Каждая строка, которую сервер пропускает через каталог."""
    keys: set[str] = set()
    for path in SERVER.glob("*.py"):
        if path.name == "i18n_texts.py":
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            name = getattr(node.func, "id", None) or getattr(node.func, "attr", None)
            if name in ("t", "t_reason") and node.args and _literal(node.args[0]):
                keys.add(_literal(node.args[0]))
            if name == "plural":
                keys.update(_literal(a) for a in node.args[1:] if _literal(a))
            # Отказы ящика и входа показываются человеку через `t(str(e))`.
            if name in ("Full", "GoogleError") and node.args:
                arg = node.args[0]
                if _literal(arg):
                    keys.add(_literal(arg))
                elif isinstance(arg, ast.BinOp) and _literal(arg.left):
                    # «обмен кода не удался: %s» — `t_reason` переводит фразу до двоеточия.
                    keys.add(_literal(arg.left).partition(": ")[0])
    keys.add(pages.LIVES_A_DAY)
    keys.update(pages.SHORT_ANSWER)
    for title, text in pages.WHAT_WE_KEEP:
        keys.update((title, text))
    keys.update(pages.WHAT_WE_DO_NOT)

    seen: list[str] = []
    real = upload_page.t
    upload_page.t = lambda s: seen.append(s) or s
    try:
        upload_page.upload_body()
    finally:
        upload_page.t = real
    keys.update(seen)
    return {k for k in keys if CYR.search(k)}


def test_у_каждой_строки_есть_перевод_на_оба_языка():
    missing = sorted(k for k in _keys() if k not in TEXTS)
    assert not missing, "нет перевода:\n" + "\n".join(missing)


def test_английский_перевод_без_кириллицы_а_украинский_не_пустой():
    bad = [k for k, (uk, en) in TEXTS.items() if CYR.search(en) or not uk.strip() or not en.strip()]
    assert not bad, bad


def test_каталог_не_держит_мёртвых_строк():
    dead = sorted(set(TEXTS) - _keys())
    assert not dead, "строки, которых больше нет в коде:\n" + "\n".join(dead)


def test_подстановки_в_переводе_те_же_что_в_оригинале():
    def holes(s: str) -> list[str]:
        return sorted(re.findall(r"%[sd]|%\.\d+f|<[^>]+>", s))

    bad = [k for k, (uk, en) in TEXTS.items() if holes(uk) != holes(k) or holes(en) != holes(k)]
    assert not bad, bad


@pytest.mark.parametrize(
    "header, lang",
    [
        ("uk-UA,uk;q=0.9,ru;q=0.8,en;q=0.5", "uk"),
        ("ru-RU,ru;q=0.9", "ru"),
        ("en-US,en;q=0.9", "en"),
        ("de-DE,de;q=0.9,uk;q=0.3", "uk"),
        ("de-DE,fr;q=0.9", "en"),
        ("ru;q=0.5,uk;q=0.8", "uk"),
        ("uk;q=0,ru", "ru"),
        ("", "en"),
        (None, "en"),
    ],
)
def test_язык_берётся_из_заголовка_а_иначе_английский(header, lang):
    assert i18n.lang_from(header) == lang


@pytest.mark.parametrize("path", ["/privacy", "/delete-account", "/login?d=nope", "/d/nope"])
def test_страницы_на_английском_без_единой_русской_буквы(point, path):
    response = point.client.get(path, headers={"Accept-Language": "en-US,en;q=0.9"})
    assert '<html lang="en"' in response.text
    body = re.sub(r"<style>.*?</style>", "", response.text, flags=re.S)
    assert not CYR.search(body), CYR.search(body)


def test_страница_по_украински_для_украинского_браузера(point):
    response = point.client.get("/privacy", headers={"Accept-Language": "uk-UA,uk;q=0.9"})
    assert '<html lang="uk"' in response.text
    assert "Про ваші дані" in response.text
    assert response.headers["vary"] == "Accept-Language"


def test_без_заголовка_английский(point):
    response = point.client.get("/privacy", headers={"Accept-Language": ""})
    assert '<html lang="en"' in response.text


def test_отказ_в_json_тоже_на_языке_человека(point):
    response = point.client.get(
        "/me", headers={"Authorization": "Bearer nope", "Accept-Language": "uk"}
    )
    assert response.status_code == 401
    assert response.json()["message"] == TEXTS["Устройство не в аккаунте. Войдите заново."][0]


def test_часы_склоняются_по_правилам_языка():
    token = i18n.use("en")
    try:
        assert "1 more hour," in pages.link_lifetime(3600 + 5)
        assert "5 more hours," in pages.link_lifetime(5 * 3600 + 5)
    finally:
        i18n.reset(token)
    token = i18n.use("uk")
    try:
        assert "2 години" in pages.link_lifetime(2 * 3600 + 5)
        assert "11 годин" in pages.link_lifetime(11 * 3600 + 5)
    finally:
        i18n.reset(token)


def test_отказ_ящика_переводится_целиком():
    with pytest.raises(mailbox.Full) as e:
        raise mailbox.Full("Файл больше 50 МБ — столько сервер Point не берёт")
    token = i18n.use("en")
    try:
        assert not CYR.search(i18n.t(str(e.value)))
    finally:
        i18n.reset(token)
