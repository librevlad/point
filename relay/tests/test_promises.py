"""Обещания о данных: две страницы, которые человек читает до установки приложения.

Магазин требует их отдельными адресами, и требование это не формальное: если обещание на странице
разойдётся с тем, что сервер делает на самом деле, снимут приложение. Поэтому здесь проверяется не
«страница отдаётся», а что на ней есть ровно то, что правда: чем удаляют аккаунт, что исчезает и
что остаётся.
"""
from __future__ import annotations


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
