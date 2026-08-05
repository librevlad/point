"""Вход одним шагом: браузер открылся на том же устройстве, сверять нечего (#561).

Поток «как у телевизора» придуман для устройств без браузера — приставка, терминал. На телефоне
браузер и Point в одних руках: человек нажал «Войти» в приложении, которое сам же открыл, и код на
странице сверять ему не с чем. Здесь проверяется, что лишние шаги исчезли ровно в этом случае и
остались во всех остальных.
"""
from __future__ import annotations

import sqlite3
import urllib.parse

from point_server import db


def start_handoff(point, name: str = "Пиксель") -> dict:
    response = point.client.post(
        "/auth/start", json={"kind": "PHONE", "name": name, "handoff": True}
    )
    assert response.status_code == 200, response.text
    return response.json()


def google_state(location: str) -> str:
    return urllib.parse.parse_qs(urllib.parse.urlparse(location).query)["state"][0]


def test_страница_входа_не_спрашивает_второй_раз_а_ведёт_к_google(point):
    started = start_handoff(point)

    page = point.client.get("/login", params={"d": started["login_id"]}, follow_redirects=False)

    assert page.status_code == 303, page.text
    assert page.headers["location"].startswith("https://accounts.google.test/")


def test_после_google_человека_возвращают_в_приложение_а_не_просят_вернуться_самому(point):
    started = start_handoff(point)
    to_google = point.client.get(
        "/login", params={"d": started["login_id"]}, follow_redirects=False
    )

    done = point.client.get(
        "/auth/callback",
        params={"code": "google-code", "state": google_state(to_google.headers["location"])},
    )

    assert done.status_code == 200
    assert "point://signed-in" in done.text
    # Код на этой странице был бы бессмыслицей: сверять его не с чем и не для чего.
    assert started["user_code"] not in done.text
    assert "закрывать страницу" not in done.text


def test_вход_одним_шагом_кончается_настоящим_пропуском(point):
    started = start_handoff(point)
    to_google = point.client.get(
        "/login", params={"d": started["login_id"]}, follow_redirects=False
    )
    point.client.get(
        "/auth/callback",
        params={"code": "google-code", "state": google_state(to_google.headers["location"])},
    )

    claimed = point.client.get(
        "/auth/session/" + started["login_id"],
        headers={"Authorization": "Bearer " + started["claim_token"]},
    )

    assert claimed.status_code == 200, claimed.text
    body = claimed.json()
    assert body["status"] == "ready"
    assert body["device_token"] and body["device_id"]
    assert body["kind"] == "PHONE" and body["name"] == "Пиксель"


def test_вход_с_другого_устройства_по_прежнему_идёт_через_код(point):
    """Компьютер и чужое устройство теряют защиту, если код убрать везде."""
    started = point.start(kind="PC", name="Рабочий")

    page = point.client.get("/login", params={"d": started["login_id"]}, follow_redirects=False)

    assert page.status_code == 200
    assert started["user_code"] in page.text
    assert "Войти через Google" in page.text


def test_после_кода_страница_готово_остаётся_прежней(point):
    started = point.start(kind="PC")
    point.browser_login(started["login_id"])

    # `browser_login` уже дошёл до конца — повторим его последний шаг ради самой страницы.
    session = point.client.get(
        "/auth/session/" + started["login_id"],
        headers={"Authorization": "Bearer " + started["claim_token"]},
    )

    assert session.status_code == 200
    second = point.start(kind="PC")
    to_google = point.client.post(
        "/login",
        content=urllib.parse.urlencode({"d": second["login_id"]}),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        follow_redirects=False,
    )
    done = point.client.get(
        "/auth/callback",
        params={"code": "google-code", "state": google_state(to_google.headers["location"])},
    )
    assert second["user_code"] in done.text
    assert "point://" not in done.text


def test_адрес_возврата_не_берётся_из_запроса(point):
    """Иначе вход стал бы перенаправлением на любой чужой сайт по нашей ссылке."""
    response = point.client.post(
        "/auth/start",
        json={
            "kind": "PHONE",
            "name": "Пиксель",
            "handoff": True,
            "return_to": "https://зло.example/забрать",
            "app_return": "https://зло.example/забрать",
        },
    )
    started = response.json()
    to_google = point.client.get(
        "/login", params={"d": started["login_id"]}, follow_redirects=False
    )
    done = point.client.get(
        "/auth/callback",
        params={"code": "google-code", "state": google_state(to_google.headers["location"])},
    )

    assert "зло.example" not in done.text
    assert "point://signed-in" in done.text


def test_просроченный_вход_не_ведёт_к_google_а_говорит_что_кончился(point):
    started = start_handoff(point)
    point.clock.advance(6 * 60)

    page = point.client.get("/login", params={"d": started["login_id"]}, follow_redirects=False)

    assert page.status_code == 404
    assert "location" not in page.headers


def test_старая_база_получает_колонку_а_не_падает(tmp_path):
    """База сервера уже живёт на боевой машине: новая колонка обязана приехать к ней сама."""
    path = str(tmp_path / "old.db")
    old = sqlite3.connect(path)
    old.executescript(
        """
        CREATE TABLE logins (
          id TEXT PRIMARY KEY, claim_sha256 TEXT NOT NULL, user_code TEXT NOT NULL,
          kind TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', key_agree TEXT NOT NULL DEFAULT '',
          key_sign TEXT NOT NULL DEFAULT '', state TEXT UNIQUE, verifier TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, user_id TEXT,
          done_at INTEGER, claimed_at INTEGER
        );
        """
    )
    old.execute(
        "INSERT INTO logins (id, claim_sha256, user_code, kind, created_at, expires_at)"
        " VALUES ('l1','h','AB-123','PC',1,2)"
    )
    old.commit()
    old.close()

    db.init(path)

    conn = db.connect(path)
    try:
        columns = {r["name"] for r in conn.execute("PRAGMA table_info(logins)")}
        assert "handoff" in columns
        # Старый вход остался на месте и считается обычным — сверка кода у него не пропала.
        assert conn.execute("SELECT handoff FROM logins WHERE id = 'l1'").fetchone()[0] == 0
    finally:
        conn.close()
