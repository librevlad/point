# -*- coding: utf-8 -*-
"""Сервер удаляет по-настоящему (#476).

До этого срока уборка, «выйти» и «удалить всё моё» были написаны и покрыты тестами — и не
вызывались ниоткуда. Снаружи это выглядело как обещание: «ссылка перестанет работать сама»,
«выход стирает ящик». Здесь проверяется не то, что функции существуют, а то, что байты уходят
с диска на настоящих ручках.

Возраст файла живёт в файловом времени (`mtime`), а не в управляемых часах стенда, поэтому
старение делается `os.utime` — так же, как его увидит боевой сервер.
"""
import os
import time

from point_server import mailbox


def _only_user(blobs: str) -> str:
    """Адрес человека на диске: вход его не отдаёт, а проверять надо именно байты."""
    return os.listdir(os.path.join(blobs, "u"))[0]


def _age(path: str, seconds: int) -> None:
    """Состарить всё дерево: сервер судит по времени изменения, а не по записи в базе."""
    old = time.time() - seconds
    for root, dirs, files in os.walk(path):
        for name in files + dirs:
            os.utime(os.path.join(root, name), (old, old))
    os.utime(path, (old, old))


def test_выложенная_ссылка_перестаёт_работать_сама(point, tmp_path):
    me = point.sign_in(sub="one")
    put = point.as_device(me["device_token"], "POST", "/d", content="важное".encode("utf-8"),
                          headers={"X-Drop-Mime": "text/plain"})
    assert put.status_code == 200, put.text
    drop_id = put.headers["X-Drop-Id"]
    assert point.client.get("/d/" + drop_id).status_code == 200

    blobs = str(tmp_path / "blobs")
    _age(blobs, mailbox.TTL_SECONDS + 3600)

    # Обход делает тот, кто кладёт новое: следующее обращение и есть сторож.
    point.as_device(me["device_token"], "POST", "/d", content=b"new",
                    headers={"X-Drop-Mime": "text/plain"})

    assert point.client.get("/d/" + drop_id).status_code == 404
    # И это не «запись скрыта»: байтов на диске нет.
    assert mailbox.drop_find(blobs, drop_id) is None


def test_выход_с_устройства_стирает_его_ящик(point, tmp_path):
    me = point.sign_in(sub="two")
    other = point.sign_in(sub="two", kind="PC", device_name="ПК")

    point.as_device(me["device_token"], "POST", "/mbx/" + other["device_id"],
                    content="письмо".encode("utf-8"))
    blobs = str(tmp_path / "blobs")
    user = _only_user(blobs)
    assert mailbox.pull(blobs, user, other["device_id"]) is not None

    gone = point.as_device(me["device_token"], "POST", "/devices/" + other["device_id"] + "/revoke")
    assert gone.status_code == 200, gone.text

    assert mailbox.pull(blobs, user, other["device_id"]) is None


def test_удалить_всё_моё_уносит_байты_а_не_только_записи(point, tmp_path):
    me = point.sign_in(sub="three")
    put = point.as_device(me["device_token"], "POST", "/d", content=b"paper",
                          headers={"X-Drop-Mime": "text/plain"})
    drop_id = put.headers["X-Drop-Id"]
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    dropped = point.as_device(me["device_token"], "DELETE", "/account")
    assert dropped.status_code == 200, dropped.text

    blobs = str(tmp_path / "blobs")
    assert mailbox.drop_find(blobs, drop_id) is None
    assert mailbox.inbox_find(blobs, box) is None
    assert point.client.get("/d/" + drop_id).status_code == 404


def test_принять_файл_проходит_целиком_и_только_один_раз(point):
    """Путь «Принять файл» от начала до конца: адрес ящика называет сервер, а не клиент.

    Здесь же проверяется, что забранное подтверждается: без этого тот же файл приезжал бы на
    каждом круге ожидания, и человек получал бы его снова и снова.
    """
    me = point.sign_in(sub="five")
    opened = point.as_device(me["device_token"], "POST", "/u/open")
    assert opened.status_code == 200, opened.text
    box = opened.json()["box"]
    assert opened.json()["url"].endswith("/u/" + box)

    # Пока никто ничего не положил — «пусто», а не отказ.
    assert point.as_device(me["device_token"], "GET", "/u/" + box + "/take").status_code == 204

    # Чужой человек: пропуска нет, есть только адрес.
    assert point.client.get("/u/" + box).status_code == 200
    sent = point.client.post("/u/" + box, files={"file": ("письмо.txt", "привет".encode("utf-8"), "text/plain")})
    assert sent.status_code == 200, sent.text

    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert got.status_code == 200
    assert got.content == "привет".encode("utf-8")
    fid = got.headers["X-File-Id"]

    # До подтверждения файл на месте: разрыв связи не должен терять присланное молча.
    assert point.as_device(me["device_token"], "GET", "/u/" + box + "/take").status_code == 200

    acked = point.as_device(me["device_token"], "POST", "/u/" + box + "/ack",
                            headers={"X-File-Id": fid})
    assert acked.json()["acked"] is True
    assert point.as_device(me["device_token"], "GET", "/u/" + box + "/take").status_code == 204


def test_чужой_ящик_не_забрать(point):
    me = point.sign_in(sub="six")
    stranger = point.sign_in(sub="seven")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    point.client.post("/u/" + box, files={"file": ("f.txt", b"data", "text/plain")})

    # Адрес ящика знает и чужой браузер — он в ссылке. Забрать по нему может только владелец.
    taken = point.as_device(stranger["device_token"], "GET", "/u/" + box + "/take")
    assert taken.status_code == 204
    assert point.as_device(me["device_token"], "GET", "/u/" + box + "/take").status_code == 200


def test_свежее_обход_не_трогает(point, tmp_path):
    """Сторож не должен быть опаснее мусора: только что положенное остаётся на месте."""
    me = point.sign_in(sub="four")
    put = point.as_device(me["device_token"], "POST", "/d", content=b"fresh",
                          headers={"X-Drop-Mime": "text/plain"})
    drop_id = put.headers["X-Drop-Id"]

    point.as_device(me["device_token"], "POST", "/u/open")

    assert point.client.get("/d/" + drop_id).status_code == 200


def test_ящик_закрывается_по_просьбе_и_чужой_не_закрыть(point):
    """Дверь закрывается, когда в неё больше не ждут (#729)."""
    me = point.sign_in(sub="closing")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    stranger = point.sign_in(sub="stranger")
    refused = point.as_device(stranger["device_token"], "POST", "/u/" + box + "/close")
    assert refused.json()["closed"] is False
    assert point.client.get("/u/" + box).status_code == 200

    closed = point.as_device(me["device_token"], "POST", "/u/" + box + "/close")
    assert closed.json()["closed"] is True

    # Ссылка перестала работать для того, кому её отдали.
    assert point.client.get("/u/" + box).status_code == 404
