"""Файлы человека — на диск кусками, мимо памяти (охота 27.11.2026).

Сервер Point — машина на 964 МБ памяти, общая с другими службами. Письмо своему устройству,
файл ссылки и присланное на страницу приёма раньше держались в памяти целиком, до 50 МБ
каждое, а предел двери был местом человека — 500 МБ. Владелец: «Так, лагодь» и «продолжай по тз».

Обещание: файл пишется на диск по мере прихода, сверх одного файла сервер не читает, отказ не
оставляет за собой ни байта, а что пришло — забирается байт в байт.
"""
from __future__ import annotations

import os
import time

import pytest

from point_server import app as app_mod, db, mailbox, store

SMALL_BLOB = 2_000_000


@pytest.fixture
def small_blob(monkeypatch):
    """Предел одного файла — 2 МБ, чтобы не гонять в тестах 50."""
    monkeypatch.setattr(mailbox, "MAX_BLOB", SMALL_BLOB)
    return SMALL_BLOB


def _chunks(data: bytes, size: int = 65536):
    for i in range(0, len(data), size):
        yield data[i:i + size]


def _staged(tmp_path) -> list[str]:
    staging = tmp_path / "blobs" / mailbox.STAGING
    return sorted(os.listdir(staging)) if staging.is_dir() else []


def test_письмо_кусками_забирается_байт_в_байт(point, small_blob):
    me = point.sign_in(sub="stream", kind="PHONE")
    pc = point.sign_in(sub="stream", kind="PC")
    letter = bytes(range(256)) * (SMALL_BLOB // 256 - 1)

    put = point.as_device(me["device_token"], "POST", "/mbx/" + pc["device_id"], content=_chunks(letter))
    got = point.as_device(pc["device_token"], "GET", "/mbx/" + pc["device_id"])

    assert put.status_code == 200, put.text
    assert got.status_code == 200
    assert got.content == letter


def test_файл_сверх_предела_отбивается_и_не_оставляет_следа(point, small_blob, tmp_path):
    me = point.sign_in(sub="big", kind="PHONE")
    pc = point.sign_in(sub="big", kind="PC")
    big = b"z" * (SMALL_BLOB + 300_000)

    letter = point.as_device(me["device_token"], "POST", "/mbx/" + pc["device_id"], content=_chunks(big))
    drop = point.as_device(me["device_token"], "POST", "/d", content=_chunks(big))

    assert letter.status_code in (413, 507), letter.text
    assert drop.status_code in (413, 507), drop.text
    assert "50" in letter.json()["message"], "отказ называет предел словами, а не кодом"
    assert point.as_device(pc["device_token"], "GET", "/mbx/" + pc["device_id"]).status_code == 204
    assert _staged(tmp_path) == [], "недописанное осталось лежать на диске"


def test_страница_приёма_берёт_файл_больше_мегабайта_и_отбивает_лишний(point, small_blob, tmp_path):
    me = point.sign_in(sub="inbox", kind="PHONE")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    ok = b"a" * (SMALL_BLOB - 100_000)
    over = b"b" * (SMALL_BLOB + 100_000)

    sent = point.client.post("/u/" + box, files={"file": ("снимок.bin", ok, "application/octet-stream")})
    refused = point.client.post("/u/" + box, files={"file": ("больше.bin", over, "application/octet-stream")})
    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")

    assert sent.status_code == 200, sent.text
    assert refused.status_code in (413, 507), refused.text
    assert got.content == ok
    assert _staged(tmp_path) == []


def test_недописанное_после_обрыва_убирает_уборка(tmp_path):
    root = str(tmp_path / "blobs")
    orphan = mailbox.staging_path(root)
    with open(orphan, "wb") as f:
        f.write(b"half")
    fresh = mailbox.staging_path(root)
    with open(fresh, "wb") as f:
        f.write(b"now")
    old = time.time() - mailbox.STAGING_TTL_SECONDS - 60
    os.utime(orphan, (old, old))

    mailbox.sweep(root)

    assert not os.path.exists(orphan)
    assert os.path.exists(fresh), "уборка не должна трогать то, что пишется прямо сейчас"


def test_предел_двери_файлов_один_файл_а_не_место_человека():
    limit = app_mod.body_limit_for("POST", "/mbx/dev-1")

    assert limit >= mailbox.MAX_BLOB, "одному файлу должно хватать"
    assert limit < mailbox.MAX_TOTAL_BYTES, "дверь файлов не должна читать всё место человека"


def test_страница_приёма_знает_предел_файла_и_отказ_по_размеру():
    """Чужой человек с видео на 70 МБ слышит «файл слишком большой», а не «попробуйте ещё раз»."""
    from point_server import upload_page

    script = upload_page.UPLOAD_SCRIPT
    assert str(mailbox.MAX_BLOB) in script, "предел файла на странице разошёлся с сервером"
    assert "x.status===413" in script, "отказ по размеру до чтения (413) страница не узнаёт"


def test_страница_приёма_принимает_только_multipart(point):
    """Поле urlencoded разбор формы держал бы в памяти целиком — страница шлёт только multipart."""
    me = point.sign_in(sub="urlenc", kind="PHONE")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    r = point.client.post("/u/" + box, data={"text": "x" * 10})

    assert r.status_code == 400


def test_долгая_отправка_после_удаления_аккаунта_не_рождает_ссылку(point, tmp_path):
    """«Удалить всё моё» посреди отправки: ссылка не должна появиться после удаления."""
    me = point.sign_in(sub="gone", kind="PHONE")
    other = point.sign_in(sub="gone", kind="PC")

    def body():
        yield b"a" * 1000
        # То же, что делает DELETE /account с другого устройства, — пока отправка ещё идёт.
        conn = db.connect(point.db_path)
        try:
            who = store.device_by_token(conn, other["device_token"], point.clock())
            store.delete_account(conn, who.user_id)
            mailbox.forget_user(str(tmp_path / "blobs"), who.user_id)
        finally:
            conn.close()
        yield b"b" * 1000

    put = point.as_device(me["device_token"], "POST", "/d", content=body(), headers={"X-Drop-Mime": "text/plain"})

    assert put.status_code == 401, put.text
    assert not (tmp_path / "blobs" / "u").exists() or not any((tmp_path / "blobs" / "u").rglob("blob.bin"))
    assert _staged(tmp_path) == []


def test_одновременных_отправок_одному_человеку_не_больше_трёх(point):
    """Счёт отправок — в памяти процесса: точный и чистый после перезапуска (проверка рецензентами)."""
    me = point.sign_in(sub="busy", kind="PHONE")
    conn = db.connect(point.db_path)
    try:
        uid = store.device_by_token(conn, me["device_token"], point.clock()).user_id
    finally:
        conn.close()
    point.client.app.state.uploads[("own", uid)] = mailbox.MAX_IN_FLIGHT
    try:
        r = point.as_device(me["device_token"], "POST", "/d", content=b"one more", headers={"X-Drop-Mime": "text/plain"})
    finally:
        point.client.app.state.uploads.pop(("own", uid), None)

    # Временный отказ — 503 с «повторите позже», а не 507: 507 клиенты читают как «слишком большой».
    assert r.status_code == 503, r.text
    assert r.json()["error"] == "busy"
    assert r.headers.get("Retry-After")


def test_замершее_тело_обрывается_а_не_держит_место():
    """Ни uvicorn, ни Caddy медленное тело не обрывают — это делает прослойка предела."""
    import asyncio

    from point_server import bodylimit

    class Stalled(Exception):
        pass

    async def app(scope, receive, send):
        while True:
            message = await receive()
            if not message.get("more_body"):
                return

    async def receive_then_silence():
        if not getattr(receive_then_silence, "sent", False):
            receive_then_silence.sent = True
            return {"type": "http.request", "body": b"part", "more_body": True}
        await asyncio.sleep(3600)

    limited = bodylimit.BodyLimit(app, limit_for=lambda m, p: 10_000, refusal=lambda p: Exception(p),
                                  stalled=lambda p: Stalled(p), idle_seconds=0.2)

    async def go():
        await limited({"type": "http", "method": "POST", "path": "/d", "headers": []},
                      receive_then_silence, lambda m: None)

    with pytest.raises(Stalled):
        asyncio.run(go())


# --- то, что нашла повторная проверка независимыми рецензентами (27.11.2026) ---------------


def test_выкладка_не_рвёт_отправки_а_сироты_никого_не_держат(tmp_path, clock):
    """Новый процесс при старте не трогает каталог приёма: старый в это время ещё дописывает
    отправки (выкладка: pkill, пауза, старт). А сироты умершего процесса никого не держат —
    отправки считаются в памяти; их недописанное уберёт уборка через час."""
    from test_ai_trial import FakeAi, _server

    root = str(tmp_path / "blobs")
    writing = [mailbox.staging_path(root, "someone") for _ in range(mailbox.MAX_IN_FLIGHT)]
    for path in writing:
        with open(path, "wb") as f:
            f.write(b"half")

    h = _server(tmp_path, clock, FakeAi())
    try:
        assert all(os.path.exists(p) for p in writing), "старт нового процесса стёр чужие отправки"
        me = h.sign_in(sub="someone", kind="PHONE")
        r = h.as_device(me["device_token"], "POST", "/d", content=b"fresh", headers={"X-Drop-Mime": "text/plain"})
        assert r.status_code == 200, r.text
    finally:
        h.client.__exit__(None, None, None)


def test_чужого_на_странице_приёма_не_держат_отправки_владельца(point):
    me = point.sign_in(sub="owner-busy", kind="PHONE")
    conn = db.connect(point.db_path)
    try:
        uid = store.device_by_token(conn, me["device_token"], point.clock()).user_id
    finally:
        conn.close()
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    point.client.app.state.uploads[("own", uid)] = mailbox.MAX_IN_FLIGHT
    try:
        sent = point.client.post("/u/" + box, files={"text": (None, "Крещатик 22")})
    finally:
        point.client.app.state.uploads.pop(("own", uid), None)

    assert sent.status_code == 200, sent.text


def test_файл_ровно_на_пределе_проходит_страницу_приёма(point, small_blob):
    """Обёртка формы — не файл: файл ровно в 50 МБ страница пропускает, и сервер тоже."""
    me = point.sign_in(sub="edge", kind="PHONE")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    sent = point.client.post(
        "/u/" + box,
        files={"file": ("видео з дуже довгою назвою українською.mp4", b"e" * SMALL_BLOB, "video/mp4")},
    )

    assert sent.status_code == 200, sent.text


def test_удалённый_ящик_не_рождается_заново(tmp_path):
    """«Удалить всё моё», пока чужая отправка шла: каталог человека не должен появиться снова."""
    root = str(tmp_path / "blobs")
    box_id = mailbox.inbox_open(root, "gone-user")
    box = mailbox.inbox_find(root, box_id)
    staged = mailbox.stage_bytes(root, b"late file", "gone-user")
    mailbox.forget_user(root, "gone-user")

    with pytest.raises(mailbox.Gone):
        mailbox.inbox_accept_file(box, staged, "f.bin", "application/octet-stream")

    assert not os.path.exists(os.path.join(root, "u", "gone-user"))
    assert not os.path.exists(staged)


def test_прослойка_без_срока_тишины_всё_равно_считает_байты():
    import asyncio

    from point_server import bodylimit

    class TooBig(Exception):
        pass

    async def app(scope, receive, send):
        while True:
            message = await receive()
            if not message.get("more_body"):
                return

    chunks = iter([b"a" * 600, b"b" * 600, b""])

    async def receive():
        body = next(chunks)
        return {"type": "http.request", "body": body, "more_body": bool(body)}

    limited = bodylimit.BodyLimit(app, limit_for=lambda m, p: 1000, refusal=lambda p: TooBig(p))

    with pytest.raises(TooBig):
        asyncio.run(limited({"type": "http", "method": "POST", "path": "/d", "headers": []},
                            receive, lambda m: None))


def test_отказ_по_размеру_на_странице_приёма_приходит_страницей(point, small_blob):
    """Форма без скрипта показывает ответ как есть — человеку нужны слова, а не JSON."""
    me = point.sign_in(sub="nojs", kind="PHONE")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    limit = app_mod.body_limit_for("POST", "/u/" + box)
    body = b"--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n\r\n" + b"z" * (limit + 10)

    r = point.client.post("/u/" + box, content=_chunks(body),
                          headers={"Content-Type": "multipart/form-data; boundary=x"})

    assert r.status_code == 413
    assert r.headers["content-type"].startswith("text/html")



def test_чужих_отправок_в_ящики_человека_не_больше_трёх_разом(point):
    me = point.sign_in(sub="crowd", kind="PHONE")
    conn = db.connect(point.db_path)
    try:
        uid = store.device_by_token(conn, me["device_token"], point.clock()).user_id
    finally:
        conn.close()
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    point.client.app.state.uploads[("receive", uid)] = mailbox.MAX_IN_FLIGHT
    try:
        sent = point.client.post("/u/" + box, files={"text": (None, "ещё одно")})
    finally:
        point.client.app.state.uploads.pop(("receive", uid), None)

    assert sent.status_code == 503
    assert sent.headers.get("Retry-After")
    assert sent.headers["content-type"].startswith("text/html")


def test_текст_на_странице_приёма_считается_в_место_один_раз(point, monkeypatch, tmp_path):
    me = point.sign_in(sub="tight", kind="PHONE")
    conn = db.connect(point.db_path)
    try:
        uid = store.device_by_token(conn, me["device_token"], point.clock()).user_id
    finally:
        conn.close()
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    text = "Крещатик 22, забрать до 19:00"
    room = len(text.encode("utf-8")) + 10
    monkeypatch.setattr(mailbox, "MAX_TOTAL_BYTES", mailbox.used_bytes(str(tmp_path / "blobs"), uid) + room)

    sent = point.client.post("/u/" + box, files={"text": (None, text)})

    assert sent.status_code == 200, sent.text


def test_на_странице_приёма_размер_и_место_это_разные_отказы(point, small_blob, monkeypatch):
    """413 — файл больше предела; 507 — у человека кончилось место, и чужому — его слова."""
    me = point.sign_in(sub="two-refusals", kind="PHONE")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    big = point.client.post("/u/" + box, files={"file": ("big.bin", b"x" * (SMALL_BLOB + 1000), "application/octet-stream")})
    monkeypatch.setattr(mailbox, "MAX_TOTAL_BYTES", 1)
    full = point.client.post("/u/" + box, files={"text": (None, "адрес")})

    assert big.status_code == 413
    assert full.status_code == 507
    assert "новую ссылку" in full.text or "нове посилання" in full.text or "new link" in full.text


def test_отдача_не_срывается_заявленной_длиной_у_get(point):
    """У GET тела нет — чужой заголовок Content-Length не должен ломать отдачу файла."""
    me = point.sign_in(sub="get-len", kind="PHONE")
    put = point.as_device(me["device_token"], "POST", "/d", content=b"z" * 5000, headers={"X-Drop-Mime": "application/octet-stream"})
    drop_id = put.headers["X-Drop-Id"]

    got = point.client.get("/d/" + drop_id + "?raw=1", headers={"Content-Length": str(10 ** 9)})

    assert got.status_code == 200
    assert got.content == b"z" * 5000
