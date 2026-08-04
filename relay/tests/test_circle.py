"""Круг устройств: у человека их несколько, отзыв действует немедленно."""
from __future__ import annotations


def test_у_человека_несколько_телефонов_и_пк(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE", device_name="Пиксель")
    second = point.sign_in(sub="sub-1", kind="PHONE", device_name="Старый телефон")
    pc = point.sign_in(sub="sub-1", kind="PC", device_name="Рабочий ПК")

    circle = point.as_device(phone["device_token"], "GET", "/circle").json()
    names = sorted(d["name"] for d in circle["devices"])
    assert names == ["Пиксель", "Рабочий ПК", "Старый телефон"]
    assert {d["kind"] for d in circle["devices"]} == {"PHONE", "PC"}
    # Все три — один человек: вход по тому же `google_sub` не заводит второго.
    assert len({d["id"] for d in circle["devices"]}) == 3
    assert [d["self"] for d in circle["devices"] if d["id"] == phone["device_id"]] == [True]
    assert second["device_id"] != pc["device_id"]


def test_почта_меняется_а_человек_остаётся(point):
    """Человек узнаётся по `google_sub`, а не по почте."""
    first = point.sign_in(sub="sub-1", email="старая@example.com")
    point.sign_in(sub="sub-1", email="новая@example.com")

    circle = point.as_device(first["device_token"], "GET", "/circle").json()
    assert len(circle["devices"]) == 2
    assert circle["account"]["email"] == "новая@example.com"


def test_enroll_объявляет_кругу_открытые_ключи(point):
    phone = point.sign_in(sub="sub-1", kind="PHONE", device_name="Пиксель")
    pc = point.sign_in(sub="sub-1", kind="PC", device_name="ПК")

    enrolled = point.as_device(
        pc["device_token"],
        "POST",
        "/enroll",
        json={"name": "Домашний ПК", "key_agree": "AGREE-PUB", "key_sign": "SIGN-PUB"},
    )
    assert enrolled.status_code == 200
    assert enrolled.json()["code"], "код сверки считается от отпечатка ключей"

    seen = [
        d
        for d in point.as_device(phone["device_token"], "GET", "/circle").json()["devices"]
        if d["id"] == pc["device_id"]
    ][0]
    assert seen["name"] == "Домашний ПК"
    assert seen["key_agree"] == "AGREE-PUB" and seen["key_sign"] == "SIGN-PUB"
    assert seen["code"] == enrolled.json()["code"]


def test_без_ключей_кода_сверки_нет(point):
    phone = point.sign_in(sub="sub-1")
    device = point.as_device(phone["device_token"], "GET", "/me").json()["device"]
    assert device["code"] == "", "кода ни от чего не бывает"


def test_отозванное_устройство_получает_401_следующим_же_запросом(point):
    phone = point.sign_in(sub="sub-1", device_name="Потерянный")
    pc = point.sign_in(sub="sub-1", kind="PC", device_name="ПК")

    assert point.as_device(phone["device_token"], "GET", "/circle").status_code == 200
    revoked = point.as_device(pc["device_token"], "POST", "/devices/%s/revoke" % phone["device_id"])
    assert revoked.status_code == 200 and revoked.json()["self"] is False

    dead = point.as_device(phone["device_token"], "GET", "/circle")
    assert dead.status_code == 401
    assert dead.json()["error"] == "no_pass"
    assert dead.headers["www-authenticate"] == "Bearer"

    left = point.as_device(pc["device_token"], "GET", "/circle").json()["devices"]
    assert [d["id"] for d in left] == [pc["device_id"]]


def test_отключить_себя_это_выйти(point):
    phone = point.sign_in(sub="sub-1")
    out = point.as_device(phone["device_token"], "POST", "/devices/%s/revoke" % phone["device_id"])
    assert out.status_code == 200 and out.json()["self"] is True
    assert point.as_device(phone["device_token"], "GET", "/circle").status_code == 401


def test_повторный_отзыв_не_притворяется_успехом(point):
    phone = point.sign_in(sub="sub-1")
    pc = point.sign_in(sub="sub-1", kind="PC")
    point.as_device(pc["device_token"], "POST", "/devices/%s/revoke" % phone["device_id"])
    again = point.as_device(pc["device_token"], "POST", "/devices/%s/revoke" % phone["device_id"])
    assert again.status_code == 404


def test_удалить_всё_моё_стирает_аккаунт_немедленно(point):
    phone = point.sign_in(sub="sub-1")
    pc = point.sign_in(sub="sub-1", kind="PC")

    assert point.as_device(phone["device_token"], "DELETE", "/account").status_code == 200
    assert point.as_device(phone["device_token"], "GET", "/circle").status_code == 401
    assert point.as_device(pc["device_token"], "GET", "/circle").status_code == 401

    # Тот же человек может войти снова — и это уже чистая учётная запись.
    fresh = point.sign_in(sub="sub-1")
    assert len(point.as_device(fresh["device_token"], "GET", "/circle").json()["devices"]) == 1


def test_на_связи_считается_по_последнему_запросу(point):
    phone = point.sign_in(sub="sub-1")
    pc = point.sign_in(sub="sub-1", kind="PC")
    point.clock.advance(600)
    circle = point.as_device(phone["device_token"], "GET", "/circle").json()["devices"]
    by_id = {d["id"]: d for d in circle}
    assert by_id[phone["device_id"]]["online"] is True, "спросивший только что был на связи"
    assert by_id[pc["device_id"]]["online"] is False


def test_без_пропуска_ручки_молчат(point):
    for method, path in (
        ("GET", "/circle"),
        ("GET", "/me"),
        ("POST", "/enroll"),
        ("DELETE", "/account"),
    ):
        assert point.client.request(method, path, json={}).status_code == 401
    assert point.as_device("made-up-pass", "GET", "/circle").status_code == 401
