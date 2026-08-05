"""Стенд для тестов сервера: без сети и без боевого сервера.

Вход по Google подменяется фейком того же шва (`GoogleIdentity`), время — управляемыми
часами, база — файлом во временном каталоге. Ни один тест не ходит наружу.
"""
from __future__ import annotations

import pathlib
import sys
import urllib.parse

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from point_server import app as app_mod  # noqa: E402
from point_server.config import Settings  # noqa: E402
from point_server.google import GoogleError, GoogleUser  # noqa: E402


class FakeGoogle:
    """Google, которого нет: возвращает того человека, которого попросили в тесте."""

    def __init__(self) -> None:
        self.person = GoogleUser(sub="sub-default", email="default@example.com", name="Кто-то")
        self.authorize_calls: list[tuple[str, str]] = []
        self.exchanges: list[tuple[str, str]] = []
        self.fail_with: str | None = None

    def authorize_url(self, *, state: str, challenge: str) -> str:
        self.authorize_calls.append((state, challenge))
        return "https://accounts.google.test/o/oauth2/v2/auth?" + urllib.parse.urlencode(
            {"state": state, "code_challenge": challenge, "code_challenge_method": "S256"}
        )

    def exchange(self, *, code: str, verifier: str) -> GoogleUser:
        if self.fail_with:
            raise GoogleError(self.fail_with)
        self.exchanges.append((code, verifier))
        return self.person


class Clock:
    def __init__(self, start: int = 1_800_000_000) -> None:
        self.value = start

    def __call__(self) -> int:
        return self.value

    def advance(self, seconds: int) -> None:
        self.value += seconds


class Harness:
    """Сервер плюс два действия, которые в тестах нужны постоянно: войти и позвать ручку."""

    def __init__(self, client: TestClient, google: FakeGoogle, clock: Clock, db_path: str):
        self.client = client
        self.google = google
        self.clock = clock
        self.db_path = db_path

    # --- вход целиком, как его прошёл бы человек ---------------------------------------

    def start(self, kind: str = "PHONE", name: str = "") -> dict:
        response = self.client.post("/auth/start", json={"kind": kind, "name": name})
        assert response.status_code == 200, response.text
        return response.json()

    def browser_login(self, login_id: str, code: str = "google-code") -> str:
        """Человек открыл страницу, сверил код и нажал «Войти через Google»."""
        page = self.client.get("/login", params={"d": login_id})
        assert page.status_code == 200, page.text
        go = self.client.post(
            "/login",
            content=urllib.parse.urlencode({"d": login_id}),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            follow_redirects=False,
        )
        assert go.status_code == 303, go.text
        state = urllib.parse.parse_qs(urllib.parse.urlparse(go.headers["location"]).query)["state"][0]
        done = self.client.get("/auth/callback", params={"code": code, "state": state})
        assert done.status_code == 200, done.text
        return state

    def sign_in(
        self,
        *,
        sub: str,
        email: str = "",
        name: str = "Человек",
        kind: str = "PHONE",
        device_name: str = "",
    ) -> dict:
        self.google.person = GoogleUser(sub=sub, email=email or (sub + "@example.com"), name=name)
        started = self.start(kind=kind, name=device_name or kind.lower())
        self.browser_login(started["login_id"])
        claimed = self.client.get(
            "/auth/session/" + started["login_id"],
            headers={"Authorization": "Bearer " + started["claim_token"]},
        )
        assert claimed.status_code == 200, claimed.text
        out = claimed.json()
        out["login"] = started
        return out

    # --- обычный запрос от вошедшего устройства ----------------------------------------

    def as_device(self, token: str, method: str, path: str, **kwargs):
        headers = kwargs.pop("headers", {})
        headers["Authorization"] = "Bearer " + token
        return self.client.request(method, path, headers=headers, **kwargs)


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def google() -> FakeGoogle:
    return FakeGoogle()


@pytest.fixture
def point(tmp_path, google: FakeGoogle, clock: Clock) -> Harness:
    db_path = str(tmp_path / "point.db")
    settings = Settings(
        root=str(tmp_path),
        db_path=db_path,
        public_url="https://point.test",
        google_client_id="",
        google_client_secret="",
        # Обход просроченного — на каждом обращении: в жизни он раз в четверть часа, но тест
        # не должен ждать четверть часа, чтобы увидеть, что обещание «сутки» выполняется.
        sweep_every=0,
    )
    application = app_mod.create_app(settings=settings, google=google, now=clock)
    with TestClient(application) as client:
        yield Harness(client, google, clock, db_path)
