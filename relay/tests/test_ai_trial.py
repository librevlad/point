"""AI без своего ключа: пробные действия через сервер Point (#477).

Владелец 27.11.2026, на модель «Pro за AI без ключа» и первым шагом пробную квоту: «Да, так
и делаем». Здесь проверяется то, что обещано человеку: проба работает без ключа, считается
только то, на что сервис ответил, кончается честным отказом, а содержимое на сервере не
остаётся.
"""
from __future__ import annotations

import io
import json
import sqlite3
import urllib.error

import pytest
from fastapi.testclient import TestClient

from conftest import Clock, FakeGoogle, Harness
from point_server import ai as ai_mod, app as app_mod
from point_server.config import Settings

ANSWER = {"choices": [{"message": {"role": "assistant", "content": "Накладна 4815-2342"}}]}
ASK = {"model": "point-trial", "messages": [{"role": "user", "content": "что на снимке?"}]}
SECRET = "тайный-текст-человека"


class FakeAi(ai_mod.AiGateway):
    def __init__(self, status: int = 200):
        self.status = status
        self.asked: list = []

    @property
    def available(self) -> bool:
        return True

    def ask(self, messages, max_tokens):
        self.asked.append((messages, max_tokens))
        if self.status != 200:
            return self.status, b"", ""
        return 200, json.dumps(ANSWER).encode(), "fake"


def _server(tmp_path, clock, ai, **over) -> Harness:
    settings = Settings(
        root=str(tmp_path), db_path=str(tmp_path / "point.db"), public_url="https://point.test",
        google_client_id="", google_client_secret="", sweep_every=0, **over,
    )
    google = FakeGoogle()
    application = app_mod.create_app(settings=settings, google=google, now=clock, ai=ai)
    client = TestClient(application, headers={"Accept-Language": "ru"})
    client.__enter__()
    return Harness(client, google, clock, settings.db_path)


@pytest.fixture
def fake_ai() -> FakeAi:
    return FakeAi()


@pytest.fixture
def trial(tmp_path, clock, fake_ai):
    h = _server(tmp_path, clock, fake_ai, ai_trial=3, ai_daily_cap=100)
    yield h
    h.client.__exit__(None, None, None)


def ask(h: Harness, token: str, body=None):
    return h.as_device(token, "POST", "/ai/v1/chat/completions", json=body or ASK)


def test_без_своего_ключа_ответ_приходит_голосом_openai(trial, fake_ai):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")

    r = ask(trial, phone["device_token"])

    assert r.status_code == 200, r.text
    assert r.json() == ANSWER
    assert r.headers["X-Point-Ai-Left"] == "2"
    assert fake_ai.asked[0][0] == ASK["messages"]


def test_счёт_виден_до_первого_действия_и_после(trial):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    before = trial.as_device(phone["device_token"], "GET", "/ai/quota").json()

    ask(trial, phone["device_token"])

    after = trial.as_device(phone["device_token"], "GET", "/ai/quota").json()
    assert before == {"available": True, "left": 3, "trial": 3}
    assert after["left"] == 2


def test_проба_кончается_честным_отказом_в_формате_openai(trial):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    for _ in range(3):
        assert ask(trial, phone["device_token"]).status_code == 200

    over = ask(trial, phone["device_token"])

    assert over.status_code == 402
    assert over.json()["error"]["type"] == "trial_over"
    assert "задайте свой ключ" in over.json()["error"]["message"]


def test_проба_общая_на_все_устройства_одного_человека(trial):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    pc = trial.sign_in(sub="sub-1", kind="PC")
    for _ in range(3):
        ask(trial, phone["device_token"])

    assert ask(trial, pc["device_token"]).status_code == 402


def test_отказ_сервиса_пробу_не_съедает(tmp_path, clock):
    h = _server(tmp_path, clock, FakeAi(status=503), ai_trial=3)
    try:
        phone = h.sign_in(sub="sub-1", kind="PHONE")

        r = ask(h, phone["device_token"])

        assert r.status_code == 502
        assert h.as_device(phone["device_token"], "GET", "/ai/quota").json()["left"] == 3
    finally:
        h.client.__exit__(None, None, None)


def test_дневной_предел_на_всех_держит_деньги(tmp_path, clock):
    h = _server(tmp_path, clock, FakeAi(), ai_trial=30, ai_daily_cap=2)
    try:
        a = h.sign_in(sub="sub-1", kind="PHONE")
        b = h.sign_in(sub="sub-2", kind="PHONE")
        ask(h, a["device_token"])
        ask(h, b["device_token"])

        busy = ask(h, a["device_token"])
        clock.advance(24 * 3600)
        next_day = ask(h, a["device_token"])

        assert busy.status_code == 429
        assert busy.json()["error"]["type"] == "day_over"
        assert next_day.status_code == 200
    finally:
        h.client.__exit__(None, None, None)


def test_без_ключей_у_сервера_пробы_нет(tmp_path, clock):
    h = _server(tmp_path, clock, None)
    try:
        phone = h.sign_in(sub="sub-1", kind="PHONE")

        assert h.as_device(phone["device_token"], "GET", "/ai/quota").json()["available"] is False
        assert ask(h, phone["device_token"]).status_code == 503
    finally:
        h.client.__exit__(None, None, None)


def test_предложение_пробы_видно_без_входа(trial, tmp_path, clock):
    """Человеку, который ещё не вошёл, Point говорит правду: есть ли проба и сколько в ней."""
    assert trial.client.get("/ai/offer").json() == {"available": True, "trial": 3}

    h = _server(tmp_path / "off", clock, None)
    try:
        assert h.client.get("/ai/offer").json()["available"] is False
    finally:
        h.client.__exit__(None, None, None)


def test_без_пропуска_пробы_нет(trial):
    trial.sign_in(sub="sub-1", kind="PHONE")

    assert trial.client.post("/ai/v1/chat/completions", json=ASK).status_code == 401
    assert trial.client.get("/ai/quota").status_code == 401


def test_слишком_большой_запрос_не_уходит_наружу(trial, fake_ai):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    huge = {"messages": [{"role": "user", "content": "x" * (app_mod.AI_MAX_BYTES + 10)}]}

    r = ask(trial, phone["device_token"], huge)

    assert r.status_code == 413
    assert fake_ai.asked == []


def test_содержимое_запроса_на_сервере_не_остаётся(trial):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    ask(trial, phone["device_token"], {"messages": [{"role": "user", "content": SECRET}]})

    conn = sqlite3.connect(trial.db_path)
    dump = "\n".join(conn.iterdump())
    conn.close()
    assert SECRET not in dump
    for path in (trial.db_path, trial.db_path + "-wal"):
        try:
            with open(path, "rb") as f:
                assert SECRET.encode() not in f.read(), path
        except FileNotFoundError:
            pass


def test_удаление_аккаунта_стирает_счёт(trial):
    phone = trial.sign_in(sub="sub-1", kind="PHONE")
    ask(trial, phone["device_token"])

    trial.as_device(phone["device_token"], "DELETE", "/account")

    conn = sqlite3.connect(trial.db_path)
    assert list(conn.execute("SELECT * FROM ai_quota")) == []
    conn.close()


# --- наверху только те, кто не учится на присланном ------------------------------------


def test_наверху_только_groq_и_cloudflare_и_только_с_ключами():
    assert ai_mod.upstreams("", "", "") == []
    names = [u.name for u in ai_mod.upstreams("g", "c", "acct")]
    assert names == ["groq", "cloudflare"]
    assert [u.name for u in ai_mod.upstreams("", "c", "")] == []


def test_отказ_первого_сервиса_ведёт_ко_второму():
    calls = []

    def opener(req, timeout):
        calls.append(req.full_url)
        if "groq" in req.full_url:
            raise urllib.error.HTTPError(req.full_url, 429, "busy", {}, io.BytesIO(b"{}"))
        return io.BytesIO(json.dumps(ANSWER).encode())

    gw = ai_mod.HttpAiGateway(ai_mod.upstreams("g", "c", "acct"), opener=opener)
    status, body, who = gw.ask(ASK["messages"], 100)

    assert status == 200
    assert who == "cloudflare"
    assert json.loads(body) == ANSWER
    assert len(calls) == 2


def test_модель_выбирает_сервер_а_не_телефон():
    sent = []

    def opener(req, timeout):
        sent.append(json.loads(req.data))
        return io.BytesIO(json.dumps(ANSWER).encode())

    gw = ai_mod.HttpAiGateway(ai_mod.upstreams("g", "", ""), opener=opener)
    gw.ask(ASK["messages"], 100)

    assert sent[0]["model"] == "qwen/qwen3.8-27b"
    assert sent[0]["max_tokens"] == 100
