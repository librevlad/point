"""Сервер аккаунтов Point: вход по Google, круг устройств, изоляция (#471).

Три вещи, которые здесь важнее удобства:

1. **Слепота.** Этот срез не возит ни байта содержимого. Он знает, чьи устройства, — и всё.
2. **Изоляция — форма запроса, а не проверка** (см. `store.py`).
3. **Ни один секрет не попадает в раздаваемый артефакт.** Учётные данные Google живут
   только здесь, в окружении службы; устройство в OAuth не участвует вовсе.

Поток входа «как у телевизора»:

    устройство                     сервер                          Google
        │ POST /auth/start ──────────►│
        │ ◄── login_id + пропуск на забор + код «K7-42Q» + адрес страницы
        │ открыть браузер ───────────►│ GET /login?d=<login_id>  (код сверяется глазами)
        │                             │ ── Authorization Code + PKCE ──►│
        │                             │ ◄──────── id_token (sub, email) │
        │ GET /auth/session/<login_id>│
        │ ◄── device_id + device_token (один раз)
"""
from __future__ import annotations

import os

import base64
import hashlib
import hmac
import sqlite3
import time
import urllib.parse
from dataclasses import dataclass
from typing import Callable, Iterator

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import HTTPException
from fastapi import Response
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import db, google as google_mod, ids, mailbox, pages, store
from .config import Settings, settings_from_env

DEVICE_KINDS = ("PHONE", "PC")


@dataclass
class Deps:
    settings: Settings
    google: google_mod.GoogleIdentity
    now: Callable[[], int]


# --- тела запросов ---------------------------------------------------------------------


def one_line(value: str) -> str:
    """Имена и ключи едут строками — перевод строки в значении сломал бы чужой разбор."""
    return (value or "").replace("\r", " ").replace("\n", " ").replace("\t", " ").strip()


class StartRequest(BaseModel):
    kind: str = "PHONE"
    name: str = Field(default="", max_length=64)
    key_agree: str = Field(default="", max_length=512)
    key_sign: str = Field(default="", max_length=512)


class EnrollRequest(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    key_agree: str | None = Field(default=None, max_length=512)
    key_sign: str | None = Field(default=None, max_length=512)


def fail(status: int, code: str, message: str, headers: dict | None = None):
    return HTTPException(status_code=status, detail={"error": code, "message": message}, headers=headers)


# --- приложение ------------------------------------------------------------------------



def _upload_form(box_id: str) -> str:
    """Страница для чужого человека: одно поле и одна кнопка, без слов о Point и его устройстве."""
    return (
        "<h1>Отправить файл</h1>"
        "<p>Выберите файл — он придёт человеку, который дал вам эту ссылку.</p>"
        "<form method=post enctype=multipart/form-data>"
        "<input type=file name=file required>"
        "<button type=submit>Отправить</button>"
        "</form>"
    )

def create_app(
    settings: Settings | None = None,
    google: google_mod.GoogleIdentity | None = None,
    now: Callable[[], int] | None = None,
) -> FastAPI:
    settings = settings or settings_from_env()
    if google is None:
        google = (
            google_mod.HttpGoogleIdentity(
                settings.google_client_id, settings.google_client_secret, settings.redirect_uri
            )
            if settings.google_configured
            else google_mod.UnconfiguredGoogle()
        )
    db.init(settings.db_path)

    app = FastAPI(title="Point server", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.deps = Deps(settings=settings, google=google, now=now or (lambda: int(time.time())))

    def deps(request: Request) -> Deps:
        return request.app.state.deps

    def conn_of(request: Request) -> Iterator[sqlite3.Connection]:
        conn = db.connect(request.app.state.deps.settings.db_path)
        try:
            yield conn
        finally:
            conn.close()

    def bearer(request: Request) -> str:
        raw = request.headers.get("Authorization", "")
        return raw[7:].strip() if raw[:7].lower() == "bearer " else ""

    def caller(
        request: Request,
        conn: sqlite3.Connection = Depends(conn_of),
        d: Deps = Depends(deps),
    ) -> store.Caller:
        who = store.device_by_token(conn, bearer(request), d.now())
        if not who:
            # Отозванное устройство попадает сюда же — и это ровно то, чего мы хотели:
            # «Отключить» действует на следующем же запросе, без списка отозванных.
            raise fail(
                401,
                "no_pass",
                "Устройство не в аккаунте. Войдите заново.",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return who

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(request: Request, exc: StarletteHTTPException):
        body = exc.detail if isinstance(exc.detail, dict) else {
            "error": "http_%d" % exc.status_code,
            "message": str(exc.detail),
        }
        return JSONResponse(body, status_code=exc.status_code, headers=exc.headers)

    @app.middleware("http")
    async def _hygiene(request: Request, call_next):
        response = await call_next(request)
        # `no-referrer` — не украшение: без него адрес страницы входа (в нём `login_id`)
        # уехал бы в Google заголовком Referer.
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    # --- здоровье ----------------------------------------------------------------------

    @app.get("/health")
    def health() -> PlainTextResponse:
        return PlainTextResponse("ok")

    # --- вход --------------------------------------------------------------------------

    @app.post("/auth/start")
    def auth_start(
        body: StartRequest,
        conn: sqlite3.Connection = Depends(conn_of),
        d: Deps = Depends(deps),
    ):
        kind = one_line(body.kind).upper()
        if kind not in DEVICE_KINDS:
            raise fail(400, "bad_kind", "Устройство бывает PHONE или PC.")
        now = d.now()
        store.sweep_logins(conn, now)  # просроченные входы уходят сами, отдельного сторожа нет
        login_id, claim_token, code = ids.opaque(32), ids.opaque(32), ids.user_code()
        store.create_login(
            conn,
            login_id=login_id,
            claim_token=claim_token,
            code=code,
            kind=kind,
            name=one_line(body.name),
            key_agree=one_line(body.key_agree),
            key_sign=one_line(body.key_sign),
            now=now,
            ttl=d.settings.login_ttl,
        )
        return {
            "login_id": login_id,
            # Пропуск на забор: `login_id` знает и браузер (он в адресе страницы), поэтому
            # забирать пропуск аккаунта по одному лишь `login_id` было бы дырой.
            "claim_token": claim_token,
            "user_code": code,
            "login_url": d.settings.login_url(login_id),
            "expires_in": d.settings.login_ttl,
            "interval": d.settings.poll_interval,
        }

    @app.get("/login", response_class=HTMLResponse)
    def login_page(d: str = "", conn: sqlite3.Connection = Depends(conn_of), dep: Deps = Depends(deps)):
        row = store.login(conn, d, dep.now())
        if not row or row["claimed_at"]:
            return HTMLResponse(pages.gone_page(), status_code=404)
        return HTMLResponse(pages.login_page(row["id"], row["user_code"]))

    @app.post("/login")
    async def login_go(
        request: Request,
        conn: sqlite3.Connection = Depends(conn_of),
        dep: Deps = Depends(deps),
    ):
        # Тело формы разбираем сами: одна строка `urllib` против ещё одной зависимости.
        raw = (await request.body()).decode("utf-8", "replace")
        login_id = urllib.parse.parse_qs(raw).get("d", [""])[0]
        row = store.login(conn, login_id, dep.now())
        if not row or row["claimed_at"] or row["done_at"]:
            return HTMLResponse(pages.gone_page(), status_code=404)
        verifier = ids.opaque(32)
        challenge = (
            base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest())
            .decode("ascii")
            .rstrip("=")
        )
        # `state` заводится только сейчас и НЕ равен `login_id`: адресу, по которому
        # устройство забирает пропуск, нечего делать в адресной строке Google.
        state = ids.opaque(32)
        store.start_oauth(conn, login_id, state, verifier)
        try:
            url = dep.google.authorize_url(state=state, challenge=challenge)
        except google_mod.GoogleError as e:
            return HTMLResponse(pages.failed_page(str(e)), status_code=503)
        return RedirectResponse(url, status_code=303)

    @app.get("/auth/callback", response_class=HTMLResponse)
    def auth_callback(
        code: str = "",
        state: str = "",
        error: str = "",
        conn: sqlite3.Connection = Depends(conn_of),
        d: Deps = Depends(deps),
    ):
        now = d.now()
        row = store.login_by_state(conn, state, now)
        if not row or row["done_at"] or row["claimed_at"]:
            return HTMLResponse(pages.gone_page(), status_code=404)
        if error or not code:
            return HTMLResponse(pages.failed_page("Google не подтвердил вход."), status_code=400)
        try:
            person = d.google.exchange(code=code, verifier=row["verifier"])
        except google_mod.GoogleError as e:
            return HTMLResponse(pages.failed_page(str(e)), status_code=502)
        user_id = store.upsert_user(
            conn,
            google_sub=person.sub,
            email=one_line(person.email),
            name=one_line(person.name),
            now=now,
        )
        store.finish_login(conn, row["id"], user_id, now)
        return HTMLResponse(pages.done_page(row["user_code"]))

    @app.get("/auth/session/{login_id}")
    def auth_session(
        login_id: str,
        request: Request,
        conn: sqlite3.Connection = Depends(conn_of),
        d: Deps = Depends(deps),
    ):
        now = d.now()
        row = store.login(conn, login_id, now)
        # Просроченный, чужой и уже забранный вход отвечают одинаково: «такого нет».
        # Разные ответы рассказали бы перебирающему, какой из адресов существует.
        if (
            not row
            or row["claimed_at"]
            or not hmac.compare_digest(row["claim_sha256"], ids.sha256_hex(bearer(request)))
        ):
            raise fail(404, "no_login", "Вход не найден или уже завершён. Начните заново.")
        if not row["done_at"] or not row["user_id"]:
            return JSONResponse(
                {"status": "pending", "user_code": row["user_code"], "interval": d.settings.poll_interval},
                status_code=202,
            )
        if not store.claim_login(conn, login_id, now):
            raise fail(404, "no_login", "Вход уже забран.")
        device_id = store.add_device(
            conn,
            user_id=row["user_id"],
            kind=row["kind"],
            name=row["name"],
            key_agree=row["key_agree"],
            key_sign=row["key_sign"],
            now=now,
        )
        token = store.issue_token(conn, device_id, now)
        person = store.user(conn, row["user_id"])
        return {
            "status": "ready",
            "device_id": device_id,
            "device_token": token,
            "kind": row["kind"],
            "name": row["name"],
            "account": {"email": person["email"], "name": person["name"]},
        }

    # --- круг устройств ----------------------------------------------------------------

    def device_json(row: sqlite3.Row, me: store.Caller, now: int, window: int) -> dict:
        return {
            "id": row["id"],
            "kind": row["kind"],
            "name": row["name"],
            "key_agree": row["key_agree"],
            "key_sign": row["key_sign"],
            "code": ids.device_code(row["key_agree"], row["key_sign"]),
            "created_at": row["created_at"],
            "last_seen": row["last_seen"],
            "online": (now - row["last_seen"]) <= window,
            "self": row["id"] == me.device_id,
        }

    @app.post("/enroll")
    def enroll(
        body: EnrollRequest,
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        """Устройство объявляет кругу своё имя и свои ОТКРЫТЫЕ ключи.

        В круг оно попало входом — отдельного «пейринга» больше нет. Здесь оно лишь
        рассказывает остальным, чем с ним говорить. Закрытые половины ключей сервер не
        видит никогда: они не покидают устройство (проект, раздел 4).
        """
        store.update_device(
            conn,
            user_id=me.user_id,
            device_id=me.device_id,
            name=None if body.name is None else one_line(body.name),
            key_agree=None if body.key_agree is None else one_line(body.key_agree),
            key_sign=None if body.key_sign is None else one_line(body.key_sign),
        )
        row = store.device(conn, me.user_id, me.device_id)
        if not row:  # pragma: no cover - устройство отозвали между двумя запросами
            raise fail(401, "no_pass", "Устройство отключено.")
        return device_json(row, me, d.now(), d.settings.online_window)

    @app.get("/circle")
    def circle(
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        now = d.now()
        rows = store.circle(conn, me.user_id)
        return {
            "account": {"email": me.email, "name": me.name},
            "devices": [device_json(r, me, now, d.settings.online_window) for r in rows],
        }

    @app.get("/me")
    def me_(
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        row = store.device(conn, me.user_id, me.device_id)
        return {
            "account": {"email": me.email, "name": me.name},
            "device": device_json(row, me, d.now(), d.settings.online_window),
        }

    @app.post("/devices/{device_id}/revoke")
    def revoke(
        device_id: str,
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        """«Потерял телефон» — и он теряет доступ на следующем же своём запросе.

        Отключить себя — это «Выйти»: отдельной ручки для выхода не заводим.
        """
        if not store.revoke_device(conn, user_id=me.user_id, device_id=device_id, now=d.now()):
            raise fail(404, "no_device", "Такого устройства в вашем круге нет.")
        # Пропуск отозван — но письма, лежащие в ящике этого устройства, остались бы на диске
        # навсегда. «Выйти» означает «меня здесь больше нет», а не «я больше не захожу».
        mailbox.forget_device(os.path.join(d.settings.root, "blobs"), me.user_id, device_id)
        return {"revoked": device_id, "self": device_id == me.device_id}

    @app.delete("/account")
    def delete_account(
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        """«Удалить всё моё» — учётная запись, все устройства и все байты немедленно.

        Записи без байтов — это не удаление, а обещание удаления: файл, выложенный по ссылке,
        продолжал бы раздаваться и после того, как человек ушёл.
        """
        store.delete_account(conn, me.user_id)
        mailbox.forget_user(os.path.join(d.settings.root, "blobs"), me.user_id)
        return {"deleted": True}

    # --- Ящики, ссылки и приём файлов под аккаунтом (#476) --------------------------------
    #
    # Переезд с релея. Пропуск — токен устройства, а не знание адреса; `user_id` берётся из
    # пропуска и входит в каждое обращение к диску. Три ручки ниже намеренно открыты для чужого
    # браузера: у человека, которому дали ссылку, пропуска нет и быть не может.

    def _blobs_root(d: Deps) -> str:
        return os.path.join(d.settings.root, "blobs")

    # Обещание «сутки» держится обходом, а не комментарием. Сторожа отдельным потоком не заводим —
    # тем же приёмом, что и просроченные входы: обход делает тот, кто кладёт новое. Дерево
    # обходится целиком, поэтому чаще раза в четверть часа не ходим.
    # Часы `d.now()` сюда не годятся: возраст файла читается с диска (`mtime`), а это другая
    # шкала — подставив в неё управляемое время стенда, обход снёс бы только что созданное.
    _swept = {"at": 0.0}

    def _sweep_blobs(d: Deps) -> None:
        now = time.time()
        if now - _swept["at"] < d.settings.sweep_every:
            return
        _swept["at"] = now
        mailbox.sweep(_blobs_root(d))

    @app.post("/mbx/{device_id}")
    async def mbx_push(
        device_id: str,
        request: Request,
        me: store.Caller = Depends(caller),
        conn: sqlite3.Connection = Depends(conn_of),
        d: Deps = Depends(deps),
    ):
        """Положить письмо в ящик СВОЕГО устройства: чужому в него не написать."""
        _sweep_blobs(d)
        if not store.device(conn, me.user_id, device_id):
            raise fail(404, "no_device", "Такого устройства в вашем круге нет.")
        data = await request.body()
        try:
            bid = mailbox.push(_blobs_root(d), me.user_id, device_id, data)
        except mailbox.Full as e:
            raise fail(507, "full", str(e))
        return PlainTextResponse(bid, headers={"X-Blob-Id": bid})

    @app.get("/mbx/{device_id}")
    def mbx_pull(device_id: str, me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        got = mailbox.pull(_blobs_root(d), me.user_id, device_id)
        if not got:
            return Response(status_code=204)
        bid, data = got
        return Response(data, media_type="application/octet-stream", headers={"X-Blob-Id": bid})

    @app.post("/mbx/{device_id}/ack")
    def mbx_ack(device_id: str, blob: str = "", me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        """Письмо удаляется подтверждением, а не выдачей: на разрыве связи оно бы потерялось."""
        return {"acked": mailbox.ack(_blobs_root(d), me.user_id, device_id, blob)}

    @app.post("/d")
    async def drop_put(request: Request, me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        _sweep_blobs(d)
        data = await request.body()
        raw = request.headers.get("X-Drop-Name", "")
        try:
            name = base64.b64decode(raw).decode("utf-8") if raw else "file"
        except Exception:
            name = "file"
        try:
            did = mailbox.drop_put(_blobs_root(d), me.user_id, data, name,
                                   request.headers.get("X-Drop-Mime", "application/octet-stream"))
        except mailbox.Full as e:
            raise fail(507, "full", str(e))
        return PlainTextResponse(did, headers={"X-Drop-Id": did})

    @app.get("/d/{drop_id}")
    def drop_get(drop_id: str, d: Deps = Depends(deps)):
        """Открыто нарочно: ссылка и есть пропуск, и эти байты сервер видит (названная цена)."""
        got = mailbox.drop_find(_blobs_root(d), drop_id)
        if not got:
            return PlainTextResponse("Файл больше не доступен", status_code=404)
        path, name, mime = got
        with open(path, "rb") as f:
            data = f.read()
        quoted = urllib.parse.quote(name)
        return Response(data, media_type=mime,
                        headers={"Content-Disposition": "attachment; filename*=UTF-8''" + quoted})

    @app.post("/u/open")
    def inbox_open(me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        _sweep_blobs(d)
        try:
            box = mailbox.inbox_open(_blobs_root(d), me.user_id)
        except mailbox.Full as e:
            raise fail(507, "full", str(e))
        return {"box": box, "url": d.settings.public_url.rstrip("/") + "/u/" + box}

    @app.get("/u/{box_id}")
    def inbox_page(box_id: str, d: Deps = Depends(deps)):
        """Страница, на которой ЧУЖОЙ человек кладёт файл. Пропуска у него нет."""
        if not mailbox.inbox_find(_blobs_root(d), box_id):
            return HTMLResponse(pages.link_gone_page(), status_code=404)
        return HTMLResponse(pages.page("Отправить файл", _upload_form(box_id)))

    @app.post("/u/{box_id}")
    async def inbox_accept(box_id: str, request: Request, d: Deps = Depends(deps)):
        box = mailbox.inbox_find(_blobs_root(d), box_id)
        if not box:
            return HTMLResponse(pages.link_gone_page(), status_code=404)
        form = await request.form()
        item = form.get("file")
        if item is None:
            return HTMLResponse(pages.too_big_page("Файл не выбран — вернитесь и выберите его."),
                                status_code=400)
        data = await item.read()
        try:
            mailbox.inbox_accept(box, data, getattr(item, "filename", "file") or "file",
                                 getattr(item, "content_type", "") or "application/octet-stream")
        except mailbox.Full as e:
            return HTMLResponse(pages.too_big_page(str(e)), status_code=507)
        return HTMLResponse(pages.sent_page())

    @app.get("/u/{box_id}/take")
    def inbox_take(box_id: str, me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        """Забрать присланное — уже под пропуском: ящик свой."""
        got = mailbox.inbox_take(_blobs_root(d), me.user_id, box_id)
        if not got:
            return Response(status_code=204)
        fid, data, name, mime = got
        return Response(data, media_type=mime, headers={
            "X-File-Id": fid,
            "X-File-Name": base64.b64encode(name.encode("utf-8")).decode("ascii"),
        })

    @app.post("/u/{box_id}/ack")
    def inbox_ack(box_id: str, request: Request, me: store.Caller = Depends(caller),
                  d: Deps = Depends(deps)):
        """«Файл дошёл» — только после этого он уходит из ящика."""
        fid = request.headers.get("X-File-Id", "")
        return {"acked": mailbox.inbox_ack(_blobs_root(d), me.user_id, box_id, fid)}

    return app
