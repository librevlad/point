"""Вход «как у телевизора»: устройство ждёт, человек ходит в браузер."""
from __future__ import annotations

import urllib.parse


def test_здоровье_отвечает_без_пропуска(point):
    # Второе слово ответа — выложенный код (#1231), про него отдельно в test_deployed_code.py.
    assert point.client.get("/health").text.startswith("ok")


def test_вход_проходит_целиком_и_выдаёт_пропуск(point):
    started = point.start(kind="PHONE", name="Пиксель")
    assert started["login_url"].endswith("/login?d=" + started["login_id"])
    assert started["user_code"] and "-" in started["user_code"]

    # Пока человек не подтвердил — устройство ждёт, а не получает отказ.
    waiting = point.client.get(
        "/auth/session/" + started["login_id"],
        headers={"Authorization": "Bearer " + started["claim_token"]},
    )
    assert waiting.status_code == 202
    assert waiting.json()["status"] == "pending"

    point.google.person = point.google.person.__class__(
        sub="sub-1", email="один@example.com", name="Первый"
    )
    point.browser_login(started["login_id"])

    ready = point.client.get(
        "/auth/session/" + started["login_id"],
        headers={"Authorization": "Bearer " + started["claim_token"]},
    )
    assert ready.status_code == 200
    body = ready.json()
    assert body["status"] == "ready"
    assert body["device_token"] and body["device_id"]
    assert body["account"] == {"email": "один@example.com", "name": "Первый"}
    assert body["kind"] == "PHONE" and body["name"] == "Пиксель"


def test_код_на_экране_и_на_странице_один_и_тот_же(point):
    started = point.start()
    page = point.client.get("/login", params={"d": started["login_id"]}).text
    assert started["user_code"] in page


def test_пропуск_забирается_один_раз(point):
    session = point.sign_in(sub="sub-1")
    again = point.client.get(
        "/auth/session/" + session["login"]["login_id"],
        headers={"Authorization": "Bearer " + session["login"]["claim_token"]},
    )
    assert again.status_code == 404


def test_без_пропуска_на_забор_вход_не_отдаётся(point):
    """`login_id` знает и браузер — поэтому одного его для забора мало."""
    started = point.start()
    point.browser_login(started["login_id"])

    assert point.client.get("/auth/session/" + started["login_id"]).status_code == 404
    assert (
        point.client.get(
            "/auth/session/" + started["login_id"],
            headers={"Authorization": "Bearer someone-elses-guess"},
        ).status_code
        == 404
    )
    # И настоящий пропуск после этого по-прежнему работает: чужие попытки не сожгли вход.
    assert (
        point.client.get(
            "/auth/session/" + started["login_id"],
            headers={"Authorization": "Bearer " + started["claim_token"]},
        ).status_code
        == 200
    )


def test_вход_живёт_пять_минут(point):
    started = point.start()
    point.clock.advance(301)
    assert point.client.get("/login", params={"d": started["login_id"]}).status_code == 404
    assert (
        point.client.get(
            "/auth/session/" + started["login_id"],
            headers={"Authorization": "Bearer " + started["claim_token"]},
        ).status_code
        == 404
    )


def test_адрес_забора_не_уезжает_в_google(point):
    """`state` не равен `login_id`: адресу, по которому забирают пропуск, нечего делать у Google."""
    started = point.start()
    go = point.client.post(
        "/login",
        content=urllib.parse.urlencode({"d": started["login_id"]}),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        follow_redirects=False,
    )
    location = go.headers["location"]
    assert started["login_id"] not in location
    assert started["claim_token"] not in location
    state, challenge = point.google.authorize_calls[-1]
    assert state != started["login_id"]
    assert challenge  # PKCE: сервер шлёт вызов, а проверяльщик — не устройство


def test_страница_входа_не_шлёт_referer(point):
    started = point.start()
    page = point.client.get("/login", params={"d": started["login_id"]})
    assert page.headers["referrer-policy"] == "no-referrer"


def test_чужой_state_ничего_не_открывает(point):
    point.start()
    assert point.client.get("/auth/callback", params={"code": "x", "state": "выдумка"}).status_code == 404


def test_отказ_google_виден_человеком_а_не_молчанием(point):
    started = point.start()
    point.client.post(
        "/login",
        content=urllib.parse.urlencode({"d": started["login_id"]}),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        follow_redirects=False,
    )
    state = point.google.authorize_calls[-1][0]
    point.google.fail_with = "подпись Google не проверилась"
    failed = point.client.get("/auth/callback", params={"code": "x", "state": state})
    assert failed.status_code == 502
    assert "Войти не получилось" in failed.text
    # И пропуск после этого не выдаётся: неудавшийся вход не считается входом.
    assert (
        point.client.get(
            "/auth/session/" + started["login_id"],
            headers={"Authorization": "Bearer " + started["claim_token"]},
        ).status_code
        == 202
    )


def test_вход_не_настроен_говорит_об_этом_прямо(tmp_path):
    """Клиента Google ещё нет (#469) — сервер отказывает честно, а не крутит колесо."""
    from fastapi.testclient import TestClient

    from point_server import app as app_mod
    from point_server.config import Settings

    settings = Settings(
        root=str(tmp_path),
        db_path=str(tmp_path / "p.db"),
        public_url="https://point.test",
        google_client_id="",
        google_client_secret="",
    )
    with TestClient(app_mod.create_app(settings=settings)) as client:
        started = client.post("/auth/start", json={"kind": "PC"}).json()
        response = client.post(
            "/login",
            content=urllib.parse.urlencode({"d": started["login_id"]}),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            follow_redirects=False,
        )
        assert response.status_code == 503
        assert "не настроен" in response.text


def test_вид_устройства_только_телефон_или_пк(point):
    assert point.client.post("/auth/start", json={"kind": "ТОСТЕР"}).status_code == 400
