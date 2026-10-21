"""Обещания о данных: две страницы, которые человек читает до установки приложения.

Магазин требует их отдельными адресами, и требование это не формальное: если обещание на странице
разойдётся с тем, что сервер делает на самом деле, снимут приложение. Поэтому здесь проверяется не
«страница отдаётся», а что на ней есть ровно то, что правда: чем удаляют аккаунт, что исчезает и
что остаётся.
"""
from __future__ import annotations

import base64


def test_страница_о_данных_открыта_без_пропуска(point):
    page = point.client.get("/privacy")

    assert page.status_code == 200
    # Человеку, который ещё ничего не установил, нужны три ответа: что знают, чего не делают,
    # как уйти.
    assert "Что хранится" in page.text
    assert "Чего Point не делает" in page.text
    assert "Как удалить" in page.text


def test_страница_о_данных_называет_чужие_сервисы_и_условие_их_работы(point):
    page = point.client.get("/privacy").text

    assert "Чужие сервисы" in page
    # Главное обещание продукта про облако: наружу ничего не уходит само.
    assert "до нажатия наружу не уходит ничего" in page


def test_страница_удаления_говорит_как_и_что_исчезает(point):
    page = point.client.get("/delete-account")

    assert page.status_code == 200
    assert "Мои устройства" in page.text and "Удалить аккаунт" in page.text
    assert "Что исчезает сразу" in page.text
    # И — что НЕ исчезает: объекты на устройствах сервер не хранил и удалить их не может.
    assert "Что остаётся" in page.text


def test_у_человека_без_доступа_к_приложению_есть_путь(point):
    page = point.client.get("/delete-account").text

    assert "librevlad@gmail.com" in page


def test_обе_страницы_не_тянут_ничего_чужого(point):
    """Правило страниц Point: ни одного чужого домена — ни шрифтов, ни скриптов, ни картинок."""
    for path in ("/privacy", "/delete-account"):
        page = point.client.get(path).text
        assert "http://" not in page
        assert "https://" not in page
        assert "<script" not in page.lower()


def test_удаление_аккаунта_уносит_и_записи_и_байты(point):
    """Страница обещает «сразу и навсегда» — вот проверка, что это правда."""
    session = point.sign_in(sub="sub-1", email="я@example.com")
    token = session["device_token"]

    gone = point.as_device(token, "DELETE", "/account")

    assert gone.status_code == 200 and gone.json()["deleted"] is True
    # Пропуск того же устройства больше не работает: аккаунта нет, а не «помечен удалённым».
    assert point.as_device(token, "GET", "/circle").status_code in (401, 404)


def _drop(point, token, data=b"secret bytes"):
    put = point.as_device(
        token, "POST", "/d",
        content=data,
        headers={"X-Drop-Name": base64.b64encode("заметка.txt".encode()).decode(), "X-Drop-Mime": "text/plain"},
    )
    assert put.status_code == 200, put.text
    return put.headers["X-Drop-Id"]


def test_страница_называет_себя_политикой_конфиденциальности(point):
    """Магазин требует, чтобы политика была подписана явно (#1485): «О ваших данных» могут не засчитать."""
    page = point.client.get("/privacy").text

    assert "<title>Point — политика конфиденциальности</title>" in page
    assert "<h1>Политика конфиденциальности</h1>" in page


def test_про_ссылку_для_другого_человека_страница_говорит_правду(point):
    """Байты ссылки сервер отдаёт любому браузеру без пропуска — значит, он их видит (#1485).

    Первой строкой страницы стояло «содержимое ваших объектов сервер не читает и не хранит»:
    для своих устройств правда, для этой двери нет. Рецензент магазина сверяет страницу с
    анкетой, где ссылка названа, — расхождение и есть повод снять приложение.
    """
    me = point.sign_in(sub="sub-drop", email="drop@example.com")
    did = _drop(point, me["device_token"])

    # Чужой браузер, без пропуска: сервер держит присланное открытым.
    assert point.client.get("/d/" + did + "?raw=1").content == b"secret bytes"

    page = point.client.get("/privacy").text
    assert "не читает и не хранит" not in page
    assert "Что вы отдали другому человеку по ссылке" in page
    assert "сервер видит" in page


def test_страница_называет_адрес_для_стука(point):
    """Адрес, которым компьютер будит телефон, лежит на сервере — и назван в анкете магазина."""
    me = point.sign_in(sub="sub-push", email="push@example.com")
    told = point.as_device(
        me["device_token"], "PUT", "/devices/" + me["device_id"] + "/push", content=b"fcm-address"
    )
    assert told.status_code == 200 and told.json()["heard"] is True

    assert "адрес, по которому компьютер может разбудить телефон" in point.client.get("/privacy").text


def test_чужие_сервисы_названы_поимённо(point):
    page = point.client.get("/privacy").text

    for service in ("Google Firebase", "OCR.space", "OVHcloud", "Anthropic Claude"):
        assert service in page, service
    # Через Google идёт только «зайди»: содержимое стука в push.py — одна метка, без объекта.
    assert "без содержимого" in page


def test_страница_говорит_что_приложение_берёт_на_телефоне(point):
    page = point.client.get("/privacy").text

    assert "Что Point берёт на телефоне" in page
    for what in ("Местоположение", "Микрофон", "Буфер обмена"):
        assert what in page, what


def test_удаление_гасит_и_ссылки_для_других_людей(point):
    """Страница удаления обещает, что ссылки для других гаснут сразу, — вот проверка."""
    me = point.sign_in(sub="sub-gone", email="gone@example.com")
    did = _drop(point, me["device_token"])

    assert point.as_device(me["device_token"], "DELETE", "/account").status_code == 200

    assert point.client.get("/d/" + did).status_code == 404
    assert "Все ссылки, по которым вы отдали объект другим людям" in point.client.get("/delete-account").text
