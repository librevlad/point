"""Стук в телефон: «зайди, для тебя что-то есть» (#817).

Просьба компьютера лежит на самом компьютере и ждёт, пока человек откроет Point. Стук
снимает ожидание — и не должен при этом рассказывать Google, чего именно от человека хотят.
"""
from __future__ import annotations

import pathlib
import sys

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from conftest import Harness  # noqa: E402
from point_server import app as app_mod, push  # noqa: E402
from point_server.config import Settings  # noqa: E402


class FakeKnocker:
    """Google, которого нет: запоминает, куда стучали и чем."""

    def __init__(self) -> None:
        self.knocks: list[str] = []
        self.alive = True
        self.silent = False

    def knock(self, token: str) -> bool:
        if self.silent:
            raise push.Silent("до Google не дозвониться")
        self.knocks.append(token)
        return self.alive


@pytest.fixture
def knocker() -> FakeKnocker:
    return FakeKnocker()


@pytest.fixture
def knocking(tmp_path, google, clock, knocker: FakeKnocker) -> Harness:
    settings = Settings(
        root=str(tmp_path),
        db_path=str(tmp_path / "point.db"),
        public_url="https://point.test",
        google_client_id="",
        google_client_secret="",
        sweep_every=0,
    )
    application = app_mod.create_app(
        settings=settings, google=google, now=clock, knocker=knocker
    )
    with TestClient(application) as client:
        yield Harness(client, google, clock, str(tmp_path / "point.db"))


def circle_of_two(point: Harness) -> tuple[dict, dict]:
    """Телефон и компьютер одного человека."""
    phone = point.sign_in(sub="one", kind="PHONE", device_name="телефон")
    pc = point.sign_in(sub="one", kind="PC", device_name="компьютер")
    return phone, pc


def test_phone_tells_where_to_knock(knocking: Harness, knocker: FakeKnocker):
    phone, pc = circle_of_two(knocking)

    said = knocking.as_device(phone["device_token"], "PUT", f"/devices/{phone['device_id']}/push", content=b"fcm-phone-1")
    assert said.status_code == 200, said.text
    assert said.json() == {"heard": True}

    knocked = knocking.as_device(pc["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert knocked.status_code == 200, knocked.text
    assert knocked.json() == {"knocked": True}
    assert knocker.knocks == ["fcm-phone-1"]


def test_address_is_told_only_about_itself(knocking: Harness):
    phone, pc = circle_of_two(knocking)

    stolen = knocking.as_device(pc["device_token"], "PUT", f"/devices/{phone['device_id']}/push", content=b"fcm-not-mine")
    assert stolen.status_code == 403, stolen.text


def test_knock_carries_one_word_only(knocking: Harness, knocker: FakeKnocker):
    """Через Google уходит слово «зайди» — ни объекта, ни действия, ни имени файла."""
    account = push.ServiceAccount("point-test", "server@point.test", "key")
    sent: dict = {}

    class Wire:
        def __init__(self, req, timeout=0):
            sent["url"] = req.full_url
            sent["body"] = req.data

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def read(self):
            return b"{}"

    knock = push.Knocker(account, now=lambda: 1.0, _open=Wire)
    knock._pass, knock._until = "access", 10_000.0
    assert knock.knock("fcm-phone") is True

    import json

    message = json.loads(sent["body"])["message"]
    assert message["data"] == {"knock": "outbox"}
    assert "notification" not in message


def test_no_key_means_no_knock_but_no_failure(point: Harness):
    """Ключа нет — Point работает: человек узнает о просьбе, открыв его сам."""
    phone, pc = circle_of_two(point)
    point.as_device(phone["device_token"], "PUT", f"/devices/{phone['device_id']}/push", content=b"fcm-phone")

    knocked = point.as_device(pc["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert knocked.status_code == 200, knocked.text
    assert knocked.json() == {"knocked": False, "why": "no_key"}


def test_silent_google_is_not_an_error(knocking: Harness, knocker: FakeKnocker):
    phone, pc = circle_of_two(knocking)
    knocking.as_device(phone["device_token"], "PUT", f"/devices/{phone['device_id']}/push", content=b"fcm-phone")
    knocker.silent = True

    knocked = knocking.as_device(pc["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert knocked.status_code == 200, knocked.text
    assert knocked.json() == {"knocked": False, "why": "silent"}


def test_dead_address_is_forgotten(knocking: Harness, knocker: FakeKnocker):
    """Приложение переустановили — адрес умер. Держать мёртвый адрес незачем."""
    phone, pc = circle_of_two(knocking)
    knocking.as_device(phone["device_token"], "PUT", f"/devices/{phone['device_id']}/push", content=b"fcm-gone")
    knocker.alive = False

    first = knocking.as_device(pc["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert first.json() == {"knocked": False}

    second = knocking.as_device(pc["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert second.json() == {"knocked": False, "why": "no_address"}


def test_stranger_cannot_knock(knocking: Harness):
    phone, _ = circle_of_two(knocking)
    stranger = knocking.sign_in(sub="two", kind="PC", device_name="чужой")

    knocked = knocking.as_device(stranger["device_token"], "POST", f"/devices/{phone['device_id']}/knock")
    assert knocked.status_code == 404, knocked.text
