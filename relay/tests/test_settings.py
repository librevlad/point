"""Настройки едут за человеком через сервер, и сервер их не читает (#610).

Владелец 10.08.2026: «согласовать с десктопными чтобы единообразно и синхронизировать через
сервер (аккаунт)», и на уточняющий вопрос — «всё, включая ключи, но ключи в закрытом виде».

Отсюда две проверки, которые здесь и живут: ящик работает — и остаётся ящиком.
"""
from __future__ import annotations


SEALED = "at=1000\nbody=bm9uY2U.c2VhbGVk\nw.dev-1=cHVi.bm9uY2U.a2V5"


def test_настройки_возвращаются_другому_устройству_того_же_человека(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE", device_name="Пиксель")
    pc = point.sign_in(sub="sub-1", kind="PC", device_name="ПК")

    put = point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": SEALED})
    assert put.status_code == 200, put.text

    got = point.as_device(pc["device_token"], "GET", "/settings").json()
    assert got["sealed"] == SEALED
    assert got["at"] == 1000


def test_чужой_человек_настроек_не_видит(point):
    mine = point.sign_in(sub="sub-1", kind="PHONE")
    stranger = point.sign_in(sub="sub-2", kind="PHONE")

    point.as_device(mine["device_token"], "PUT", "/settings", json={"sealed": SEALED})

    got = point.as_device(stranger["device_token"], "GET", "/settings").json()
    assert got["sealed"] == ""


def test_пока_никто_ничего_не_настроил_ответ_пустой(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    got = point.as_device(phone["device_token"], "GET", "/settings").json()
    assert got == {"sealed": "", "at": 0}


def test_старое_не_ложится_поверх_нового(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    pc = point.sign_in(sub="sub-1", kind="PC")

    point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": "at=2000\nbody=b.c"})
    stale = point.as_device(pc["device_token"], "PUT", "/settings", json={"sealed": "at=1000\nbody=x.y"})

    assert stale.status_code == 409
    assert point.as_device(pc["device_token"], "GET", "/settings").json()["at"] == 2000


def test_то_же_время_переписывает_и_не_застревает(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": "at=2000\nbody=a.a"})
    again = point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": "at=2000\nbody=b.b"})

    assert again.status_code == 200
    assert point.as_device(phone["device_token"], "GET", "/settings").json()["sealed"] == "at=2000\nbody=b.b"


def test_пустые_настройки_не_принимаются(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    refused = point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": "  "})

    assert refused.status_code == 400


def test_без_пропуска_настройки_недоступны(point):
    point.sign_in(sub="sub-1", kind="PHONE")

    assert point.client.get("/settings").status_code == 401
    assert point.client.put("/settings", json={"sealed": SEALED}).status_code == 401


def test_сервер_хранит_ровно_то_что_прислали_и_ничего_не_разбирает(point, tmp_path):
    """Ящик остаётся ящиком: в базе лежит присланная строка, а не её разобранные части."""
    import sqlite3

    phone = point.sign_in(sub="sub-1", kind="PHONE")
    point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": SEALED})

    conn = sqlite3.connect(point.db_path)
    rows = list(conn.execute("SELECT sealed, at FROM settings"))
    conn.close()

    assert rows == [(SEALED, 1000)]


def test_удаление_аккаунта_уносит_и_настройки(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": SEALED})

    point.as_device(phone["device_token"], "DELETE", "/account")

    again = point.sign_in(sub="sub-1", kind="PHONE")
    assert point.as_device(again["device_token"], "GET", "/settings").json()["sealed"] == ""
