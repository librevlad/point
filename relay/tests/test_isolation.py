"""Два человека на одном сервере не пересекаются ничем.

Проверка из задачи дословно: «Два разных аккаунта Google входят с двух устройств; ни один
не видит ни устройств, ни ящиков другого. Отозванное устройство получает 401 на следующем
же запросе.» Ящиков в этом срезе ещё нет (они приезжают #476), устройства — есть.
"""
from __future__ import annotations

import ast
import pathlib

STORE = pathlib.Path(__file__).resolve().parent.parent / "point_server" / "store.py"

# Единственное место, где владелец ВЫВОДИТСЯ из пропуска, а не подставляется в запрос.
DERIVES_OWNER = {"device_by_token"}


def test_чужой_круг_не_виден(point):
    alice = point.sign_in(sub="sub-alice", email="alice@example.com", device_name="Телефон Алисы")
    bob = point.sign_in(sub="sub-bob", email="bob@example.com", device_name="Телефон Боба")

    a_circle = point.as_device(alice["device_token"], "GET", "/circle").json()
    b_circle = point.as_device(bob["device_token"], "GET", "/circle").json()

    assert [d["name"] for d in a_circle["devices"]] == ["Телефон Алисы"]
    assert [d["name"] for d in b_circle["devices"]] == ["Телефон Боба"]
    assert a_circle["account"]["email"] == "alice@example.com"
    assert b_circle["account"]["email"] == "bob@example.com"


def test_чужое_устройство_не_отключается(point):
    alice = point.sign_in(sub="sub-alice")
    bob = point.sign_in(sub="sub-bob")

    attempt = point.as_device(bob["device_token"], "POST", "/devices/%s/revoke" % alice["device_id"])
    # 404, а не 403: разный ответ рассказал бы, что такое устройство существует.
    assert attempt.status_code == 404
    assert point.as_device(alice["device_token"], "GET", "/circle").status_code == 200


def test_чужое_удаление_аккаунта_не_задевает_соседа(point):
    alice = point.sign_in(sub="sub-alice")
    bob = point.sign_in(sub="sub-bob")

    point.as_device(bob["device_token"], "DELETE", "/account")
    assert point.as_device(alice["device_token"], "GET", "/circle").status_code == 200
    assert point.as_device(bob["device_token"], "GET", "/circle").status_code == 401


def test_чужой_enroll_не_переименовывает_соседа(point):
    alice = point.sign_in(sub="sub-alice", device_name="Алиса")
    bob = point.sign_in(sub="sub-bob", device_name="Боб")

    point.as_device(bob["device_token"], "POST", "/enroll", json={"name": "Подменённый"})
    a_names = [d["name"] for d in point.as_device(alice["device_token"], "GET", "/circle").json()["devices"]]
    assert a_names == ["Алиса"]


def test_каждый_запрос_к_устройствам_несёт_владельца():
    """Изоляция — форма запроса, а не проверка: `user_id` в каждом `WHERE`.

    Тест смотрит на исходник `store.py`: функция, трогающая таблицу `devices`, обязана
    упоминать `user_id`. Иначе однажды появится запрос без владельца, и ни один сценарный
    тест этого не заметит — он просто вернёт чужое.
    """
    tree = ast.parse(STORE.read_text(encoding="utf-8"))
    touched = []
    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        sql = " ".join(
            n.value for n in ast.walk(node) if isinstance(n, ast.Constant) and isinstance(n.value, str)
        ).lower()
        if "devices" not in sql:
            continue
        touched.append(node.name)
        if node.name in DERIVES_OWNER:
            continue
        assert "user_id" in sql, "запрос к устройствам без владельца: %s" % node.name
    assert len(touched) > 4, "тест перестал видеть запросы — значит перестал что-либо охранять"


def test_пропуск_не_лежит_в_базе_открытым(point):
    """В базе — только SHA-256 от пропуска: утечка файла не даёт войти ни в один аккаунт."""
    session = point.sign_in(sub="sub-alice")
    blob = pathlib.Path(point.db_path).read_bytes()
    for name in ("point.db-wal", "point.db-shm"):
        extra = pathlib.Path(point.db_path).with_name(name)
        if extra.exists():
            blob += extra.read_bytes()
    assert session["device_token"].encode("utf-8") not in blob
    assert session["login"]["claim_token"].encode("utf-8") not in blob
