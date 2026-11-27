"""Предел тела запроса — по пришедшим байтам и до разбора (охота 27.11.2026).

Сервер проверял размер только у пробы AI и только по заголовку `Content-Length`: тело кусками
заголовка не несёт, и 12 МБ уходили к сервису AI. А тело читалось и разбиралось раньше
проверки пропуска, и предела не было нигде — аноним мог заставить сервер держать в памяти
тело любого размера на машине, общей с другими службами. Владелец: «Так, лагодь».

Обещание двойное: лишнее не читается — и файлы человека как ездили, так и ездят.
"""
from __future__ import annotations

import json

from point_server import app as app_mod
from test_ai_trial import FakeAi, _server


def _chunks(data: bytes, size: int = 65536):
    for i in range(0, len(data), size):
        yield data[i:i + size]


def _big_ask(nbytes: int) -> bytes:
    return json.dumps({"messages": [{"role": "user", "content": "x" * nbytes}]}).encode()


def test_проба_кусками_больше_предела_не_уходит_наружу(tmp_path, clock):
    seen = FakeAi()
    h = _server(tmp_path, clock, seen, ai_trial=30, ai_daily_cap=100)
    try:
        phone = h.sign_in(sub="sub-1", kind="PHONE")

        r = h.as_device(phone["device_token"], "POST", "/ai/v1/chat/completions",
                        content=_chunks(_big_ask(app_mod.AI_MAX_BYTES * 2)),
                        headers={"Content-Type": "application/json"})

        assert r.status_code == 413
        assert r.json()["error"]["type"] == "too_big", "проба отказывает голосом OpenAI"
        assert seen.asked == [], "тело сверх предела ушло к сервису AI"
        assert h.as_device(phone["device_token"], "GET", "/ai/quota").json()["left"] == 30
    finally:
        h.client.__exit__(None, None, None)


def test_без_пропуска_большое_тело_не_разбирается(point):
    """Битое тело без пропуска раньше получало «запрос не читается» — то есть его прочли.

    Теперь всё сверх предела отбивается, не дочитанным: ни JSON, ни пропуск не нужны, чтобы
    понять, что такой запрос серверу не по росту.
    """
    junk = b"{" + b"y" * (app_mod.SMALL_MAX_BYTES * 3)

    declared = point.client.post("/enroll", content=junk, headers={"Content-Type": "application/json"})
    streamed = point.client.post("/enroll", content=_chunks(junk), headers={"Content-Type": "application/json"})

    assert declared.status_code == 413
    assert streamed.status_code == 413
    assert declared.json()["error"] == "too_big"


def test_файлы_человека_больше_мегабайта_ездят_как_прежде(point):
    """Предел мелких запросов не задевает двери, которыми едут файлы."""
    me = point.sign_in(sub="files", kind="PHONE")
    pc = point.sign_in(sub="files", kind="PC")
    big = b"z" * (app_mod.SMALL_MAX_BYTES * 3)

    letter = point.as_device(me["device_token"], "POST", "/mbx/" + pc["device_id"], content=_chunks(big))
    drop = point.as_device(me["device_token"], "POST", "/d", content=big, headers={"X-Drop-Mime": "text/plain"})
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    sent = point.client.post("/u/" + box, files={"file": ("снимок.bin", big, "application/octet-stream")})

    assert letter.status_code == 200, letter.text
    assert drop.status_code == 200, drop.text
    assert sent.status_code == 200, sent.text


def test_пределы_дверей():
    assert app_mod.body_limit_for("POST", "/ai/v1/chat/completions") == app_mod.AI_MAX_BYTES
    assert app_mod.body_limit_for("POST", "/enroll") == app_mod.SMALL_MAX_BYTES
    assert app_mod.body_limit_for("POST", "/u/open") == app_mod.SMALL_MAX_BYTES, "открыть ящик — не прислать файл"
    for upload in ("/mbx/dev-1", "/d", "/u/box-1"):
        assert app_mod.body_limit_for("POST", upload) > app_mod.AI_MAX_BYTES, upload
    assert app_mod.body_limit_for("POST", "/mbx/dev-1/ack") == app_mod.SMALL_MAX_BYTES
