"""Настройки аккаунта едут за человеком, а не за кругом.

До 26.11.2026 они ездили запечатанными под устройства круга (#610): телефон, вошедший в
аккаунт после компьютера, открыть конверт не мог и видел один ключ из двенадцати. Владелец:
«это кошмар пользователя… оно должно зависеть только от аккаунта, не от круга», из
вариантов — «Сервер хранит ключи сам».

Отсюда то, что проверяется здесь: новое устройство получает настройки сразу, без участия
старых; чужой человек их не видит; на диске они не лежат открытым текстом; удаление
аккаунта уносит их сразу.
"""
from __future__ import annotations

import sqlite3

import pytest
from fastapi.testclient import TestClient

from conftest import Clock, FakeGoogle
from point_server import app as app_mod, db
from point_server.config import Settings

SECRET = "sk-test-0123456789abcdef"
SETTINGS = f"at=1000\nai.groq={SECRET}\nai.groq.at=1000\nprivacy=ASK"


def put(point, token, text):
    return point.as_device(token, "PUT", "/account/settings", json={"settings": text})


def get(point, token):
    return point.as_device(token, "GET", "/account/settings").json()


def test_устройство_вошедшее_позже_получает_настройки_сразу(point):
    """Ровно тот случай, что сломался: телефон вошёл после того, как компьютер всё настроил."""
    pc = point.sign_in(sub="sub-1", kind="PC", device_name="ПК")
    assert put(point, pc["device_token"], SETTINGS).status_code == 200

    phone = point.sign_in(sub="sub-1", kind="PHONE", device_name="Пиксель")

    assert get(point, phone["device_token"]) == {"settings": SETTINGS, "at": 1000}


def test_чужой_человек_настроек_не_видит(point):
    mine = point.sign_in(sub="sub-1", kind="PHONE")
    stranger = point.sign_in(sub="sub-2", kind="PHONE")

    put(point, mine["device_token"], SETTINGS)

    assert get(point, stranger["device_token"]) == {"settings": "", "at": 0}


def test_пока_никто_ничего_не_настроил_ответ_пустой(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    assert get(point, phone["device_token"]) == {"settings": "", "at": 0}


def test_старое_не_ложится_поверх_нового(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    pc = point.sign_in(sub="sub-1", kind="PC")

    put(point, phone["device_token"], "at=2000\nsound=false")
    stale = put(point, pc["device_token"], "at=1000\nsound=true")

    assert stale.status_code == 409
    assert get(point, pc["device_token"]) == {"settings": "at=2000\nsound=false", "at": 2000}


def test_то_же_время_переписывает_и_не_застревает(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    put(point, phone["device_token"], "at=2000\nsound=false")
    again = put(point, phone["device_token"], "at=2000\nsound=true")

    assert again.status_code == 200
    assert get(point, phone["device_token"])["settings"] == "at=2000\nsound=true"


def test_пустые_настройки_не_принимаются(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    assert put(point, phone["device_token"], "  ").status_code == 400


def test_без_пропуска_настройки_недоступны(point):
    point.sign_in(sub="sub-1", kind="PHONE")

    assert point.client.get("/account/settings").status_code == 401
    assert point.client.put("/account/settings", json={"settings": SETTINGS}).status_code == 401


def test_прежних_запечатанных_ручек_больше_нет(point):
    """Старый клиент получает отказ, а не кладёт конверт, который сервер принял бы за настройки."""
    phone = point.sign_in(sub="sub-1", kind="PHONE")

    assert point.as_device(phone["device_token"], "GET", "/settings").status_code == 404
    old = point.as_device(phone["device_token"], "PUT", "/settings", json={"sealed": "at=1\nbody=a.b"})
    assert old.status_code in (404, 405)


def test_на_диске_ключи_не_лежат_открытым_текстом(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    put(point, phone["device_token"], SETTINGS)

    conn = sqlite3.connect(point.db_path)
    rows = list(conn.execute("SELECT data, at FROM account_settings"))
    conn.close()

    assert len(rows) == 1
    data, at = rows[0]
    assert at == 1000
    assert SECRET not in data
    assert "groq" not in data
    for path in (point.db_path, point.db_path + "-wal"):
        try:
            with open(path, "rb") as f:
                assert SECRET.encode() not in f.read(), path
        except FileNotFoundError:
            pass


def test_строка_перенесённая_к_другому_человеку_не_открывается(point):
    """Шифр привязан к человеку: подмена владельца строки в базе не отдаёт чужие ключи."""
    mine = point.sign_in(sub="sub-1", kind="PHONE")
    stranger = point.sign_in(sub="sub-2", kind="PHONE")
    put(point, mine["device_token"], SETTINGS)
    put(point, stranger["device_token"], "at=5\nsound=true")

    conn = sqlite3.connect(point.db_path)
    mine_data = conn.execute(
        "SELECT data FROM account_settings WHERE at = 1000"
    ).fetchone()[0]
    conn.execute("UPDATE account_settings SET data = ? WHERE at = 5", (mine_data,))
    conn.commit()
    conn.close()

    assert get(point, stranger["device_token"]) == {"settings": "", "at": 0}


def test_удаление_аккаунта_уносит_и_настройки(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    put(point, phone["device_token"], SETTINGS)

    point.as_device(phone["device_token"], "DELETE", "/account")

    conn = sqlite3.connect(point.db_path)
    assert list(conn.execute("SELECT * FROM account_settings")) == []
    conn.close()
    again = point.sign_in(sub="sub-1", kind="PHONE")
    assert get(point, again["device_token"])["settings"] == ""


# --- ключ шифрования -------------------------------------------------------------------


def _server(tmp_path, clock=None):
    settings = Settings(
        root=str(tmp_path),
        db_path=str(tmp_path / "point.db"),
        public_url="https://point.test",
        google_client_id="",
        google_client_secret="",
        sweep_every=0,
    )
    return app_mod.create_app(settings=settings, google=FakeGoogle(), now=clock or Clock())


def test_ключ_сервер_создаёт_сам_и_держит_между_перезапусками(point, tmp_path):
    key_file = tmp_path / "settings.key"
    assert key_file.exists(), "ключ создаётся при первом запуске, без рук человека"
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    put(point, phone["device_token"], SETTINGS)
    before = key_file.read_bytes()

    with TestClient(_server(tmp_path, point.clock)) as restarted:
        got = restarted.get(
            "/account/settings", headers={"Authorization": "Bearer " + phone["device_token"]}
        ).json()

    assert key_file.read_bytes() == before
    assert got == {"settings": SETTINGS, "at": 1000}


def test_пропал_ключ_настройки_читаются_как_пустые_и_кладутся_заново(point, tmp_path):
    phone = point.sign_in(sub="sub-1", kind="PHONE")
    put(point, phone["device_token"], SETTINGS)
    (tmp_path / "settings.key").unlink()

    with TestClient(_server(tmp_path, point.clock)) as restarted:
        auth = {"Authorization": "Bearer " + phone["device_token"]}
        assert restarted.get("/account/settings", headers=auth).json() == {"settings": "", "at": 0}
        assert restarted.put(
            "/account/settings", headers=auth, json={"settings": "at=2000\nsound=true"}
        ).status_code == 200
        assert restarted.get("/account/settings", headers=auth).json()["at"] == 2000


def test_испорченный_ключ_останавливает_запуск_а_не_заменяется_молча(tmp_path):
    (tmp_path / "settings.key").write_text("не ключ")

    with pytest.raises(RuntimeError):
        _server(tmp_path)

    assert (tmp_path / "settings.key").read_text() == "не ключ"


def test_прежняя_таблица_запечатанных_настроек_уходит_с_живой_базы(tmp_path):
    path = str(tmp_path / "point.db")
    conn = db.connect(path)
    conn.execute("CREATE TABLE settings (user_id TEXT PRIMARY KEY, sealed TEXT, at INTEGER)")
    conn.execute("INSERT INTO settings VALUES ('u', 'конверт', 1)")
    conn.close()

    db.init(path)

    conn = db.connect(path)
    tables = {r["name"] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    conn.close()
    assert "settings" not in tables
    assert "account_settings" in tables
