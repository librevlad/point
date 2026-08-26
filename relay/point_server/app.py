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
import re
import hashlib
import hmac
import sqlite3
import time
import urllib.parse
from dataclasses import dataclass
from typing import Callable, Iterator

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import HTTPException, RequestValidationError
from fastapi import Response
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import db, google as google_mod, ids, mailbox, pages, push as push_mod, store, upload_page
from .config import Settings, settings_from_env

DEVICE_KINDS = ("PHONE", "PC")

#: Куда сервер возвращает человека после входа, начатого на том же устройстве (#561).
#:
#: Константа, а не поле запроса: адрес возврата, взятый из тела `/auth/start`, превратил бы вход в
#: перенаправление на любой чужой сайт по нашей ссылке. Схема принадлежит приложению Point и
#: объявлена в его манифесте — открыть по ней чужое приложение нельзя.
APP_RETURN = "point://signed-in"


@dataclass
class Deps:
    settings: Settings
    google: google_mod.GoogleIdentity
    now: Callable[[], int]
    # Кому стучать в телефон (#817). `None` — стучать нечем, и это рабочее состояние.
    knocker: object = None


# --- тела запросов ---------------------------------------------------------------------


def one_line(value: str) -> str:
    """Имена и ключи едут строками — перевод строки в значении сломал бы чужой разбор."""
    return (value or "").replace("\r", " ").replace("\n", " ").replace("\t", " ").strip()


class StartRequest(BaseModel):
    kind: str = "PHONE"
    name: str = Field(default="", max_length=64)
    key_agree: str = Field(default="", max_length=512)
    key_sign: str = Field(default="", max_length=512)
    #: Браузер откроется на том же устройстве, откуда начали (#561). Тогда сверять код нечем и
    #: незачем: человек подтверждает вход в приложении, которое сам же секунду назад открыл.
    #: Куда возвращать, устройство НЕ говорит — адрес возврата у сервера свой (см. `APP_RETURN`),
    #: иначе ручка стала бы открытым перенаправлением на что угодно.
    handoff: bool = False


class SettingsRequest(BaseModel):
    """Запечатанные настройки. Для сервера это строка и ничего больше (#610)."""

    sealed: str = Field(default="", max_length=64_000)


class EnrollRequest(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    key_agree: str | None = Field(default=None, max_length=512)
    key_sign: str | None = Field(default=None, max_length=512)


def fail(status: int, code: str, message: str, headers: dict | None = None):
    return HTTPException(status_code=status, detail={"error": code, "message": message}, headers=headers)


# --- приложение ------------------------------------------------------------------------



def _upload_form(box_id: str) -> str:
    """Страница для чужого человека: одно действие и ни одного слова о Point.

    Разметка, стиль и скрипт живут в `upload_page` — там же объяснено, почему портал
    здесь работает индикатором отправки.
    """
    return upload_page.upload_body()


def _require_form_parsing() -> None:
    """Упасть при старте, а не по одной загрузке за раз.

    Живой отказ 2026-08-10: `python-multipart` был объявлен в requirements, но не установлен на
    сервере — и приём файла отвечал 500 на КАЖДУЮ попытку, пока сервер выглядел здоровым:
    `/health` отдавал 200, журнал молчал по существу, а человек видел, что файл «не уходит».
    Половина рабочего сервера хуже честно упавшего: упавший чинят сразу.
    """
    try:
        # Пакет переехал с `multipart` на `python_multipart`; Starlette умеет оба,
        # поэтому и проверка спрашивает оба, а не цементирует одно имя.
        try:
            import python_multipart  # noqa: F401
        except ModuleNotFoundError:
            import multipart  # noqa: F401
    except ModuleNotFoundError as e:  # pragma: no cover - проверяется тестом через подмену
        raise RuntimeError(
            "Не установлен python-multipart — без него приём файла отвечает 500 на каждую "
            "загрузку. Выполните: pip install -r requirements.txt"
        ) from e


MAX_READABLE_TEXT = 200_000

GEO_SHAPED = re.compile(r"^-?\d{1,2}\.\d+\s*,\s*-?\d{1,3}\.\d+$")


def _vcard_fields(text: str) -> tuple[str, list[tuple[str, str]]]:
    """Имя, телефоны и почты присланной карточки — ровно то, что человек хочет увидеть сразу."""
    name, fields = "", []
    for line in text.splitlines():
        head, _, value = line.partition(":")
        key = head.split(";")[0].strip().upper()
        value = value.strip()
        if not value:
            continue
        if key == "FN" and not name:
            name = value
        elif key == "TEL":
            fields.append(("Телефон", value))
        elif key == "EMAIL":
            fields.append(("Почта", value))
        elif key == "ORG":
            fields.append(("Организация", value))
        elif key == "ADR":
            fields.append(("Адрес", value.replace(";", " ").strip()))
    return name, fields


def _geo_point(text: str) -> str | None:
    """`geo:50.45,30.52` или просто пара координат — место, а не файл."""
    body = text.strip()
    if body.lower().startswith("geo:"):
        body = body[4:].split("?")[0].strip()
    return body if GEO_SHAPED.match(body) else None


def _weight(size: int) -> str:
    """Вес человеческими словами: «4,2 МБ» вместо 4404019."""
    if size < 1024:
        return "%d Б" % size
    if size < 1024 * 1024:
        return "%d КБ" % round(size / 1024)
    return ("%.1f МБ" % (size / (1024 * 1024))).replace(".", ",")


def _kind_word(mime: str, name: str) -> str:
    """Чем названо присланное на странице отдачи: PDF, архив, документ, файл."""
    base = (mime or "").split(";")[0].strip().lower()
    tail = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if base == "application/pdf" or tail == "pdf":
        return "PDF"
    if base in ("application/zip", "application/x-7z-compressed", "application/vnd.rar") or tail in ("zip", "rar", "7z"):
        return "Архив"
    if tail in ("doc", "docx", "xls", "xlsx", "ppt", "pptx"):
        return "Документ"
    if base.startswith("video/"):
        return "Видео"
    return "Файл"


def _readable_text(mime: str, data: bytes) -> bool:
    """
    Присланное можно прочитать глазами прямо на странице.

    Судим по содержимому, а не только по заявленному типу: имя файла и mime приходят от
    отправителя, а решать, что показать человеку, приходится по тому, что действительно
    лежит в байтах. Большой текст остаётся файлом — страница на мегабайт не помощь.
    """
    if len(data) > MAX_READABLE_TEXT:
        return False
    base = (mime or "").split(";")[0].strip().lower()
    if base and not (base.startswith("text/") or base in ("application/json", "application/xml")):
        return False
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return bool(text.strip()) and "\x00" not in text


# Отпечаток выложенного кода (#1231): его кладёт рядом с кодом `tools/deploy-server.sh`.
DEPLOYED_FILE = "deployed.txt"
DEPLOYED_UNKNOWN = "неизвестен"

# Ищется он именно рядом с кодом, а не в `POINT_SERVER_ROOT`: каталог данных службы и каталог,
# куда легли `.py`, — не обязательно одно и то же, а отпечаток обязан лежать ровно у того кода,
# который сейчас работает.
CODE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _deployed_code(code_dir: str) -> str:
    """
    Какой код работает на этой машине.

    Между «слито в main» и «работает у человека» не было ни одного сигнала: код правился в
    репозитории, а на боевой машине жила копия постарше — так страница приёма файла неделю
    отвечала 500 (#723), а человеческие ответы на ошибки прожили недели английскими (#1130).
    Оба раза расхождение нашёл человек, а не прогон. Теперь сервер называет себя сам, и
    сверить его с main может кто угодно, у кого есть публичный адрес.

    Файла нет — значит выкладывали не скриптом, и назвать код нечем. Это тоже ответ: он
    честнее молчания и роняет сверку так же, как расхождение.
    """
    try:
        with open(os.path.join(code_dir, DEPLOYED_FILE), encoding="utf-8") as stamp:
            first = stamp.readline().strip()
    except OSError:
        return DEPLOYED_UNKNOWN
    return first[:120] if first else DEPLOYED_UNKNOWN


def create_app(
    settings: Settings | None = None,
    google: google_mod.GoogleIdentity | None = None,
    now: Callable[[], int] | None = None,
    knocker: object | None = None,
) -> FastAPI:
    _require_form_parsing()
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
    if knocker is None:
        # Ключа нет — Point работает без стука: просьба разбирается при открытии.
        knocker = push_mod.knocker(settings.fcm_key_path)
    app.state.deps = Deps(
        settings=settings,
        google=google,
        now=now or (lambda: int(time.time())),
        knocker=knocker,
    )

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

    @app.exception_handler(RequestValidationError)
    async def _validation_error(request: Request, exc: RequestValidationError):
        """Отказ проверки тела — тем же голосом, что и свои отказы (#1130).

        Стандартный ответ FastAPI — технический английский с `loc`/`ctx` и ЭХОМ присланного
        (`input`): сервер, который не возит ни байта содержимого, возвращал бы присланное
        обратно в теле ошибки. Поэтому здесь короткая русская фраза и ничего из запроса.
        Битый JSON приходит этим же исключением (тип `json_invalid`) — различаем только
        формулировку, не раскрывая ввод.
        """
        unreadable = any(e.get("type") == "json_invalid" for e in exc.errors())
        if unreadable:
            body = {"error": "bad_json", "message": "Запрос не читается — проверьте данные и попробуйте ещё раз."}
        else:
            body = {"error": "bad_request", "message": "Запрос не подходит по форме — проверьте данные и попробуйте ещё раз."}
        return JSONResponse(body, status_code=422)

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
        # Второе слово — выложенный код (#1231). Читается на каждом запросе: ответ про то, какой
        # код работает, не должен зависеть от того, когда службу поднимали в последний раз.
        return PlainTextResponse("ok " + _deployed_code(CODE_DIR))

    # Ссылка Point у получателя с Point открывается в Point (#1083): Android проверяет
    # этот файл, прежде чем отдать https-ссылку приложению. Отпечатки — релизный ключ и
    # debug-ключ стенда; без файла система показывала бы диалог выбора, что хуже обоих.
    @app.get("/.well-known/assetlinks.json")
    def assetlinks() -> JSONResponse:
        return JSONResponse(
            [
                {
                    "relation": ["delegate_permission/common.handle_all_urls"],
                    "target": {
                        "namespace": "android_app",
                        "package_name": "com.point",
                        "sha256_cert_fingerprints": [
                            "A9:55:88:14:C0:C1:BC:30:FB:BD:CE:7C:5E:7C:55:50:A7:AB:3D:1D:C4:BE:9A:7F:53:A0:D0:DC:6A:5C:E3:19",
                            "F3:D9:DF:F6:D8:96:66:BE:F8:79:37:A4:A9:AF:3F:AF:B2:C7:F6:1C:D3:0D:BE:87:8A:FA:7C:27:90:08:20:F3",
                        ],
                    },
                },
            ],
        )

    # --- обещания о данных -------------------------------------------------------------
    #
    # Обе страницы открыты и не требуют ни пропуска, ни установленного приложения: магазин
    # требует, чтобы человек мог прочитать их до того, как что-то поставит, и чтобы уйти можно
    # было даже без доступа к телефону.

    @app.get("/privacy", response_class=HTMLResponse)
    def privacy() -> HTMLResponse:
        return HTMLResponse(pages.privacy_page())

    @app.get("/delete-account", response_class=HTMLResponse)
    def deletion() -> HTMLResponse:
        return HTMLResponse(pages.deletion_page())

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
            handoff=bool(body.handoff),
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
            # Каким будет вход, решает сервер и говорит вслух: устройству не нужно помнить, что
            # оно просило, а экрану — гадать, показывать код или нет.
            "handoff": bool(body.handoff),
        }

    def to_google(conn: sqlite3.Connection, login_id: str, dep: Deps):
        """Отправить человека к Google: PKCE, `state`, перенаправление.

        Одна функция на две двери — страницу со сверкой кода и вход одним шагом (#561), — потому
        что расходиться им не в чем: разница только в том, спрашивают ли человека лишний раз.
        """
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

    @app.get("/login", response_class=HTMLResponse)
    def login_page(d: str = "", conn: sqlite3.Connection = Depends(conn_of), dep: Deps = Depends(deps)):
        row = store.login(conn, d, dep.now())
        if not row or row["claimed_at"]:
            return HTMLResponse(pages.gone_page(), status_code=404)
        # Вход начат на этом же устройстве — страница со сверкой кода была бы лишним экраном между
        # «Войти» и выбором аккаунта. Человек нажал «Войти» секунду назад; спрашивать его «вы точно
        # хотели войти?» — это не защита, а шаг.
        if row["handoff"] and not row["done_at"]:
            return to_google(conn, row["id"], dep)
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
        return to_google(conn, login_id, dep)

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
        # Вход начат в том же устройстве — возвращаем человека в Point сами, а не просим закрыть
        # вкладку и вернуться (#561). Адрес возврата — наша собственная константа, ничего из
        # запроса в него не попадает: принимать его от клиента значило бы завести перенаправление
        # на любой чужой адрес по нашей ссылке.
        if row["handoff"]:
            return HTMLResponse(pages.return_page(APP_RETURN))
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

    # --- настройки аккаунта в закрытом виде (#610) ---------------------------------

    @app.get("/settings")
    def get_settings(
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
    ):
        row = store.settings(conn, me.user_id)
        return {"sealed": row["sealed"] if row else "", "at": row["at"] if row else 0}

    @app.put("/settings")
    def put_settings(
        body: SettingsRequest,
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        sealed = (body.sealed or "").strip()
        if not sealed:
            raise fail(400, "no_settings", "Настройки приехали пустыми.")

        # Число «когда запечатано» сервер достаёт из конверта, не заглядывая в содержимое:
        # оно и есть единственное, что ему нужно знать, чтобы не положить старое поверх нового.
        at = 0
        for line in sealed.splitlines():
            if line.startswith("at="):
                at = int(line[3:]) if line[3:].isdigit() else 0
                break
        if not store.put_settings(conn, user_id=me.user_id, sealed=sealed, at=at, now=d.now()):
            raise fail(409, "stale_settings", "На сервере лежат более новые настройки.")
        return {"ok": True, "at": at}

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

    @app.put("/devices/{device_id}/push")
    async def push_address(
        device_id: str,
        request: Request,
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
    ):
        """Устройство говорит, куда в него стучать (#817).

        Только про себя: чужой адрес здесь означал бы возможность дёргать чужой телефон.
        """
        if device_id != me.device_id:
            raise fail(403, "not_yours", "Адрес для стука устройство сообщает только про себя.")
        address = one_line((await request.body()).decode("utf-8", "replace"))
        store.set_push_address(conn, user_id=me.user_id, device_id=device_id, address=address)
        return {"heard": bool(address)}

    @app.post("/devices/{device_id}/knock")
    def knock(
        device_id: str,
        conn: sqlite3.Connection = Depends(conn_of),
        me: store.Caller = Depends(caller),
        d: Deps = Depends(deps),
    ):
        """Постучать в устройство своего круга: «зайди, для тебя что-то есть».

        Через Google уходит одно слово. Что именно ждёт человека, знает только его
        собственное устройство — оно и сходит за просьбой.

        Молчание — не ошибка: без ключа Firebase, без адреса и с мёртвым адресом Point
        продолжает работать, просто человек узнает о просьбе, когда откроет Point сам.
        """
        if not store.device(conn, me.user_id, device_id):
            raise fail(404, "no_device", "Такого устройства в вашем круге нет.")
        address = store.push_address(conn, me.user_id, device_id)
        if d.knocker is None or not address:
            return {"knocked": False, "why": "no_key" if d.knocker is None else "no_address"}
        try:
            heard = d.knocker.knock(address)
        except push_mod.Silent:
            return {"knocked": False, "why": "silent"}
        if not heard:
            # Адрес умер вместе с переустановкой приложения — держать его незачем.
            store.set_push_address(conn, user_id=me.user_id, device_id=device_id, address="")
        return {"knocked": heard}

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
    def drop_get(drop_id: str, raw: int = 0, d: Deps = Depends(deps)):
        """Открыто нарочно: ссылка и есть пропуск, и эти байты сервер видит (названная цена)."""
        got = mailbox.drop_find(_blobs_root(d), drop_id)
        if not got:
            return HTMLResponse(pages.drop_gone_page(), status_code=404)
        path, name, mime = got
        with open(path, "rb") as f:
            data = f.read()

        # Присланный текст читается прямо со страницы, а не скачивается файлом. Point держал
        # текст в руках и отдавал его в худшей форме: браузер сохранял вложение, и прочитать
        # присланное можно было только открыв его чем-то ещё. `?raw=1` отдаёт тот же файл.
        if not raw and _readable_text(mime, data):
            text = data.decode("utf-8")
            here = "/d/" + drop_id + "?raw=1"

            # Присланное показывается тем, что оно есть (#737): контакт — контактом, место —
            # картой. Файл никуда не делся и лежит рядом, для тех, кому нужен именно файл.
            if "BEGIN:VCARD" in text.upper():
                who, fields = _vcard_fields(text)
                return HTMLResponse(pages.drop_contact_page(who or name, fields, here))

            point = _geo_point(text)
            if point:
                return HTMLResponse(pages.drop_place_page(name, point, here))

            return HTMLResponse(pages.drop_text_page(name, text, here))

        # Снимок видно, запись слышно, файл хотя бы называет себя (#883). Раньше всё, кроме
        # текста, контакта и места, браузер молча качал: человек не знал ни что ему прислали,
        # ни сколько это весит, ни до когда живёт ссылка. `?raw=1` отдаёт сам файл.
        base = (mime or "").split(";")[0].strip().lower()
        here = "/d/" + drop_id + "?raw=1"
        if not raw and base.startswith("image/"):
            return HTMLResponse(pages.drop_image_page(name, here, _weight(len(data))))
        if not raw and base.startswith("audio/"):
            return HTMLResponse(pages.drop_audio_page(name, here, mime, _weight(len(data))))
        if not raw:
            return HTMLResponse(
                pages.drop_file_page(name, here, _kind_word(mime, name), _weight(len(data)))
            )

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

    @app.post("/u/{box_id}/close")
    def inbox_close(box_id: str, me: store.Caller = Depends(caller), d: Deps = Depends(deps)):
        """Дверь закрывается, когда в неё больше не ждут (#729)."""
        return {"closed": mailbox.inbox_close(_blobs_root(d), me.user_id, box_id)}

    @app.get("/u/{box_id}")
    def inbox_page(box_id: str, d: Deps = Depends(deps)):
        """Страница, на которой ЧУЖОЙ человек кладёт файл. Пропуска у него нет."""
        if not mailbox.inbox_find(_blobs_root(d), box_id):
            return HTMLResponse(pages.link_gone_page(), status_code=404)
        return HTMLResponse(
            pages.page("Отправить в Point", _upload_form(box_id), head=upload_page.UPLOAD_HEAD)
        )

    @app.post("/u/{box_id}")
    async def inbox_accept(box_id: str, request: Request, d: Deps = Depends(deps)):
        box = mailbox.inbox_find(_blobs_root(d), box_id)
        if not box:
            return HTMLResponse(pages.link_gone_page(), status_code=404)
        form = await request.form()

        # Ссылку чаще всего дают ради куска текста — адреса, номера заказа, обрывка
        # переписки (#883). Он приходит текстом, а не выдуманным файлом: на той стороне
        # Point получает обычный текстовый объект.
        item = form.get("file")
        typed = (form.get("text") or "").strip() if isinstance(form.get("text"), str) else ""
        if item is None and not typed:
            return HTMLResponse(pages.nothing_to_send_page(), status_code=400)
        if item is None:
            # Контакт и место присылаются тем же полем, но своим именем и своим типом:
            # на той стороне контакт должен стать контактом, а не «Текстом» (#916).
            given = form.get("name")
            given = given.strip() if isinstance(given, str) else ""
            if "BEGIN:VCARD" in typed.upper():
                name = given or "Контакт.vcf"
                mime = "text/vcard; charset=utf-8"
            else:
                name = given or "Текст"
                mime = "text/plain; charset=utf-8"
            data = typed.encode("utf-8")
        else:
            data = await item.read()
            name = getattr(item, "filename", "file") or "file"
            mime = getattr(item, "content_type", "") or "application/octet-stream"
        try:
            mailbox.inbox_accept(box, data, name, mime)
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
