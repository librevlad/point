#!/usr/bin/env python3
"""Point relay (#161 v2) — a blind store-and-forward mailbox.

Both the phone and the PC connect OUTBOUND to this service, so no inbound port on either device
is needed: it works across firewalls, changing IPs and different networks (LTE ↔ home). The relay
is a *blind pipe* — it never holds the pairing token or a plaintext object, only ciphertext
addressed by an opaque, token-derived mailbox id (see RelayCrypto on the app side).

Pure Python 3 stdlib (http.server + ssl). Runs as an unprivileged user process on a high port —
no root, no systemd required. Blobs live on disk under $POINT_RELAY_ROOT and self-expire (TTL),
so a restart never loses an in-flight object.

Env:
  POINT_RELAY_SECRET  required — shared build-baked secret; requests must send it as X-Point-App.
  POINT_RELAY_PORT    default 8443.
  POINT_RELAY_ROOT    default ~/point-relay (holds cert.pem, key.pem, mbx/).

Endpoints (all but /health, /d/<id> and /u/<box> require the X-Point-App secret):
  GET  /health                     → 200 "ok"
  POST /mbx/<id>        body=blob   → 200, X-Blob-Id header (store ciphertext; ≤50 MB)
  GET  /mbx/<id>?wait=N            → 200 oldest blob (+X-Blob-Id) / 204 after N s long-poll
  POST /mbx/<id>/ack   X-Blob-Id   → 200 (delete; repeat ack is still 200)
  POST /d              body=file   → 200, drop id (public download link; ≤50 MB)
  GET  /d/<id>                     → 200 the file itself (any browser, no secret)
  POST /u/<box>/open               → 200 (open a receiving box for 24 h)
  GET  /u/<box>                    → 200 a plain upload page in Russian (no secret)
  POST /u/<box>   multipart        → 200 (the file lands in mailbox <box>)
  PUT  /u/<box>?name=&mime=        → 200 (same, raw body — for curl)
"""
import http.server
import base64
import os
import re
import socketserver
import ssl
import struct
import threading
import time
import urllib.parse
import uuid

ROOT = os.path.expanduser(os.environ.get("POINT_RELAY_ROOT", "~/point-relay"))
MBX = os.path.join(ROOT, "mbx")
DROP = os.path.join(ROOT, "drop")
SECRET = os.environ.get("POINT_RELAY_SECRET", "")
PORT = int(os.environ.get("POINT_RELAY_PORT", "8443"))
CERT = os.path.join(ROOT, "cert.pem")
KEY = os.path.join(ROOT, "key.pem")
MAX_BLOB = 50 * 1024 * 1024
TTL_SECONDS = 24 * 3600
WAIT_MAX = 30

os.makedirs(MBX, exist_ok=True)
os.makedirs(DROP, exist_ok=True)


def mailbox_dir(mid):
    """A mailbox id is base64url from the app; keep only its safe chars as the directory name."""
    safe = "".join(c for c in (mid or "") if c.isalnum() or c in "-_")
    return os.path.join(MBX, safe) if safe else None


def one_line(s):
    """Метаданные едут строками — перевод строки в значении сломал бы разбор кадра."""
    return (s or "").replace("\r", " ").replace("\n", " ").replace("\t", " ").strip()


def parse_multipart(content_type, body):
    """Файл из формы браузера: (имя, тип, байты). Разбираем сами — stdlib-парсер формы устарел."""
    m = re.search(r'boundary="?([^";]+)"?', content_type or "")
    if not m:
        return "file", "application/octet-stream", b""
    sep = ("--" + m.group(1)).encode("utf-8")
    for part in body.split(sep):
        head, _, payload = part.partition(b"\r\n\r\n")
        if not payload:
            continue
        # Имя файла браузер шлёт байтами UTF-8: «отчёт.pdf» обязан остаться отчётом.
        text = head.decode("utf-8", "replace")
        if "filename=" not in text:
            continue
        name = re.search(r'filename="([^"]*)"', text)
        mime = re.search(r"Content-Type:\s*([^\r\n]+)", text, re.I)
        data = payload[:-2] if payload.endswith(b"\r\n") else payload
        name = name.group(1) if name else ""
        name = name.replace("\\", "/").rsplit("/", 1)[-1] or "file"  # старые браузеры шлют путь
        return name, (mime.group(1).strip() if mime else "application/octet-stream"), data
    return "file", "application/octet-stream", b""


# Адрес ящика приёма: то же base64url, что и остальные ящики, но длину проверяем строго — чужой
# POST сюда приходит без секрета, и коротких «на угад» адресов быть не должно.
INBOX_ID = re.compile(r"^[A-Za-z0-9_-]{22,64}$")

# Страница приёма. Ни одного чужого домена: ни шрифтов, ни скриптов, ни картинок — человек и так
# открывает незнакомую ссылку, и подтягивать в неё что-то извне было бы наглостью. Скриптов нет
# вовсе: обычная форма работает и на старом телефоне, и с выключенным JS.
PAGE_STYLE = """
*{box-sizing:border-box}
body{margin:0;padding:24px;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0E1014;color:#F2F3F5;
font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif}
main{width:100%;max-width:420px;background:linear-gradient(#1A1D25,#121419);border:1px solid #FFFFFF14;
border-radius:18px;padding:24px}
h1{margin:0 0 8px;font-size:22px;font-weight:600}
p{margin:0 0 16px;font-size:15px;line-height:1.5;color:#A8ADB8}
input[type=file]{width:100%;padding:14px;margin-bottom:16px;border:1px dashed #FFFFFF2E;border-radius:12px;
background:#00000033;color:#F2F3F5;font-size:15px}
button{width:100%;padding:14px;border:0;border-radius:12px;background:#7C5CFF;color:#fff;
font-size:16px;font-weight:600;cursor:pointer}
button:active{background:#6A4BE8}
small{display:block;margin-top:16px;font-size:13px;line-height:1.5;color:#7E8492}
a{color:#7C5CFF}
"""


def page(title, body):
    return (
        "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>%s</title><style>%s</style></head><body><main>%s</main></body></html>"
        % (title, PAGE_STYLE, body)
    )


PAGE_FORM = page(
    "Отправить файл в Point",
    "<h1>Отправить файл</h1>"
    "<p>Файл уедет на телефон, который дал вам эту ссылку.</p>"
    "<form method=\"post\" enctype=\"multipart/form-data\">"
    "<input type=\"file\" name=\"f\" required>"
    "<button type=\"submit\">Отправить</button></form>"
    "<small>До 50 МБ. Пока телефон не заберёт файл, он лежит на сервере Point — не дольше суток, "
    "потом стирается сам. Ссылка временная: у неё тот же срок.</small>",
)

PAGE_DONE = page(
    "Файл отправлен",
    "<h1>Отправлено</h1><p>Файл уехал. Можно закрывать страницу.</p>"
    "<small>Если нужно отправить ещё один — откройте ссылку снова.</small>",
)

PAGE_GONE = page(
    "Ссылка больше не работает",
    "<h1>Ссылка больше не работает</h1>"
    "<p>Такие ссылки живут сутки. Попросите новую у того, кто её дал.</p>",
)

PAGE_TOO_BIG = page(
    "Файл слишком большой",
    "<h1>Файл слишком большой</h1><p>Больше 50 МБ этим способом не проходит.</p>",
)

PAGE_EMPTY = page(
    "Файл не выбран",
    "<h1>Файл не выбран</h1><p>Вернитесь назад и выберите файл.</p>",
)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # privacy: never log requests/paths
        pass

    def reply(self, code, body=b"", headers=None):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def authed(self):
        return bool(SECRET) and self.headers.get("X-Point-App") == SECRET

    def parts(self):
        return self.path.split("?", 1)[0].strip("/").split("/")

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/health":
            return self.reply(200, b"ok")
        # Drop: файл забирают ПО ССЫЛКЕ, обычным браузером — секрета приложения у него нет и быть
        # не может. Защита здесь другая: ссылка неугадываема (160 бит) и живёт сутки.
        first = self.parts()
        if len(first) == 2 and first[0] == "d":
            return self.drop_get(first[1])
        # Обратная сторона: страница, на которой ЧУЖОЙ человек кладёт файл. Тоже без секрета.
        if len(first) == 2 and first[0] == "u":
            return self.inbox_page(first[1])
        if not self.authed():
            return self.reply(401, b"nope")
        p = self.parts()
        if len(p) == 2 and p[0] == "mbx":
            return self.pull(mailbox_dir(p[1]))
        self.reply(404, b"")

    def do_POST(self):
        p = self.parts()
        # Отправка файла со страницы приёма — единственный POST без секрета: у браузера чужого
        # человека секрета приложения нет и быть не может. Пропуск здесь — сам адрес ящика
        # (160 бит), и ящик должен быть заранее открыт телефоном (см. inbox_open).
        if len(p) == 2 and p[0] == "u":
            return self.inbox_accept(p[1], multipart=True)
        if not self.authed():
            return self.reply(401, b"nope")
        if len(p) == 1 and p[0] == "d":
            return self.drop_put()
        if len(p) == 3 and p[0] == "u" and p[2] == "open":
            return self.inbox_open(p[1])
        if len(p) == 2 and p[0] == "mbx":
            return self.push(mailbox_dir(p[1]))
        if len(p) == 3 and p[0] == "mbx" and p[2] == "ack":
            return self.ack(mailbox_dir(p[1]))
        self.reply(404, b"")

    def do_PUT(self):
        # То же, что POST со страницы, но телом целиком — так файл кладут из терминала (curl) и
        # так же его положит любой сценарий, у которого нет формы.
        p = self.parts()
        if len(p) == 2 and p[0] == "u":
            return self.inbox_accept(p[1], multipart=False)
        self.reply(404, b"")

    def push(self, box):
        if not box:
            return self.reply(400, b"")
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0 or n > MAX_BLOB:
            return self.reply(413, b"")
        data = self.rfile.read(n)
        os.makedirs(box, exist_ok=True)
        bid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
        tmp = os.path.join(box, bid + ".part")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(box, bid + ".bin"))
        self.reply(200, bid.encode(), {"X-Blob-Id": bid})

    # --- Drop: перекинуть файл туда-сюда одной ссылкой -----------------------------------
    #
    # Самый маленький способ отдать файл человеку, у которого Point не стоит: он открывает ссылку
    # и получает файл. Отдельного продукта для этого не нужно — релей уже умеет хранить байты и
    # чистить их по времени.
    #
    # Чего здесь НЕТ и это сказано прямо: релей ВИДИТ содержимое такого файла. Всё остальное, что
    # он возит (объекты, буфер), запечатано ключом пары устройств, а у ссылки для чужого человека
    # такого ключа нет. Кто отдаёт файл по ссылке — принимает, что файл лежит на сервере открытым
    # ровно сутки.

    def drop_dir(self, did):
        safe = "".join(c for c in did if c.isalnum())[:40]
        return os.path.join(DROP, safe) if safe else None

    def drop_put(self):
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0 or n > MAX_BLOB:
            return self.reply(413, b"")
        did = uuid.uuid4().hex + uuid.uuid4().hex[:8]  # 160 бит: ссылку не перебрать
        box = self.drop_dir(did)
        os.makedirs(box, exist_ok=True)
        # Имя едет в base64: в HTTP-заголовок кириллица не помещается, а «отчёт.pdf» на том конце
        # обязан остаться «отчётом», а не «îò÷¸òîì».
        raw = self.headers.get("X-Drop-Name", "")
        try:
            name = base64.b64decode(raw).decode("utf-8") if raw else "file"
        except Exception:
            name = "file"
        name = name.replace(chr(10), " ") or "file"
        mime = self.headers.get("X-Drop-Mime", "application/octet-stream").replace(chr(10), " ")
        data = self.rfile.read(n)
        with open(os.path.join(box, "meta"), "w", encoding="utf-8") as f:
            f.write(name + chr(10) + mime)
        tmp = os.path.join(box, "blob.part")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(box, "blob.bin"))
        self.reply(200, did.encode(), {"X-Drop-Id": did})

    def drop_get(self, did):
        box = self.drop_dir(did)
        blob = os.path.join(box, "blob.bin") if box else None
        if not blob or not os.path.isfile(blob):
            # Истёкшая ссылка — обычное дело, а не ошибка: drop живёт сутки и умирает сам.
            gone = "Файл больше не доступен".encode("utf-8")
            return self.reply(404, gone, {"Content-Type": "text/plain; charset=utf-8"})
        name, mime = "file", "application/octet-stream"
        try:
            with open(os.path.join(box, "meta"), encoding="utf-8") as f:
                lines = f.read().split(chr(10))
            name = lines[0] or name
            mime = (lines[1] if len(lines) > 1 else "") or mime
        except OSError:
            pass
        with open(blob, "rb") as f:
            data = f.read()
        disposition = "attachment; filename*=UTF-8''" + urllib.parse.quote(name)
        self.reply(200, data, {"Content-Type": mime, "Content-Disposition": disposition})

    # --- Приём: ссылка, по которой ЧУЖОЙ человек кладёт файл ТЕБЕ -------------------------
    #
    # Обратная сторона Drop. Телефон открывает ящик и показывает ссылку (кодом или текстом);
    # другой человек открывает её в браузере, видит страницу с выбором файла и отправляет. Файл
    # ложится в тот самый почтовый ящик `mbx/<box>`, который телефон уже умеет слушать (`pull`),
    # и уезжает к нему обычным путём.
    #
    # Что здесь названо прямо:
    #  * **Блоб плоский, а не шифротекст.** У браузера чужого человека ключа пары устройств нет и
    #    быть не может, поэтому релей ВИДИТ этот файл — ровно как в `/d`. Это плата за «работает у
    #    любого, без установки», и она такая же, как у ссылки на скачивание.
    #  * **Ящик заводит телефон** (`/u/<box>/open` — с секретом приложения). Без этого шага чужой
    #    POST не создаёт ничего: иначе неаутентифицированная запись позволяла бы набивать диск
    #    ящиками, которых никто не ждёт.
    #  * **Ящик живёт сутки**, как и всё остальное: метка `.open` — обычный файл, и её сносит тот
    #    же сборщик по TTL.

    def inbox_box(self, box):
        """Каталог ящика приёма — тот же mbx, что слушает телефон; None, если адрес не наш."""
        if not INBOX_ID.match(box or ""):
            return None
        return mailbox_dir(box)

    def inbox_open(self, box):
        d = self.inbox_box(box)
        if not d:
            return self.reply(400, b"")
        os.makedirs(d, exist_ok=True)
        # Пустая метка: её mtime и есть срок жизни ящика (сутки), сборщик снимет её сам.
        with open(os.path.join(d, ".open"), "wb"):
            pass
        return self.reply(200, b"ok")

    def inbox_ready(self, box):
        d = self.inbox_box(box)
        return d if d and os.path.isfile(os.path.join(d, ".open")) else None

    def inbox_page(self, box):
        if not self.inbox_ready(box):
            return self.html(404, PAGE_GONE)
        return self.html(200, PAGE_FORM)

    def inbox_accept(self, box, multipart):
        d = self.inbox_ready(box)
        if not d:
            return self.html(404, PAGE_GONE)
        n = int(self.headers.get("Content-Length", 0))
        if n <= 0 or n > MAX_BLOB:
            # Соединение закрываем: клиент ещё шлёт тело, и без этого он читал бы ответ в разрыв.
            self.close_connection = True
            return self.html(413, PAGE_TOO_BIG)
        body = self.rfile.read(n)
        if multipart:
            name, mime, data = parse_multipart(self.headers.get("Content-Type", ""), body)
        else:
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            name = (q.get("name") or ["file"])[0]
            mime = (q.get("mime") or ["application/octet-stream"])[0]
            data = body
        if not data:
            return self.html(400, PAGE_EMPTY)
        if len(data) > MAX_BLOB:
            return self.html(413, PAGE_TOO_BIG)
        # Кадр той же формы, что возит пара устройств (encodePcFrame): [длина шапки][шапка][байты].
        # Разбирает его на телефоне тот же самый декодер — своего формата для приёма не заводим.
        header = ("name=%s\nmime=%s\nsource=drop-in" % (one_line(name), one_line(mime))).encode("utf-8")
        blob = struct.pack(">I", len(header)) + header + data
        bid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
        tmp = os.path.join(d, bid + ".part")
        with open(tmp, "wb") as f:
            f.write(blob)
        os.replace(tmp, os.path.join(d, bid + ".bin"))
        return self.html(200, PAGE_DONE)

    def html(self, code, body):
        return self.reply(code, body.encode("utf-8"), {"Content-Type": "text/html; charset=utf-8"})

    def oldest(self, box):
        if not box or not os.path.isdir(box):
            return None
        blobs = sorted(x for x in os.listdir(box) if x.endswith(".bin"))
        return blobs[0][:-4] if blobs else None

    def pull(self, box):
        wait = 0
        q = self.path.split("?", 1)
        if len(q) == 2:
            for kv in q[1].split("&"):
                if kv.startswith("wait="):
                    try:
                        wait = min(WAIT_MAX, max(0, int(kv[5:] or 0)))
                    except ValueError:
                        wait = 0
        deadline = time.time() + wait
        while True:
            bid = self.oldest(box)
            if bid:
                with open(os.path.join(box, bid + ".bin"), "rb") as f:
                    data = f.read()
                return self.reply(
                    200, data, {"X-Blob-Id": bid, "Content-Type": "application/octet-stream"}
                )
            if time.time() >= deadline:
                return self.reply(204, b"")
            time.sleep(0.5)

    def ack(self, box):
        raw = self.headers.get("X-Blob-Id", "")
        safe = "".join(c for c in raw if c.isalnum() or c == "-")
        if box and safe:
            try:
                os.remove(os.path.join(box, safe + ".bin"))
            except FileNotFoundError:
                pass
        self.reply(200, b"ok")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def sweep_expired():
    """Сутки — обещание, а не пожелание: сюда входит и `drop`, который ссылка кладёт на скачивание.

    До этого сборщик обходил только `mbx`, а `drop` лежал вечно: обещание «файл живёт сутки»
    держалось на одном комментарии. Пустые каталоги сносим следом — иначе ящик, из которого всё
    забрали, остаётся навсегда следом того, что человек когда-то отдавал.
    """
    while True:
        cutoff = time.time() - TTL_SECONDS
        for tree in (MBX, DROP):
            for root, dirs, files in os.walk(tree, topdown=False):
                for fn in files:
                    path = os.path.join(root, fn)
                    try:
                        if os.path.getmtime(path) < cutoff:
                            os.remove(path)
                    except OSError:
                        pass
                if root != tree and not os.listdir(root):
                    try:
                        os.rmdir(root)
                    except OSError:
                        pass
        time.sleep(3600)


def main():
    if not SECRET:
        raise SystemExit("POINT_RELAY_SECRET is required")
    threading.Thread(target=sweep_expired, daemon=True).start()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    server = Server(("0.0.0.0", PORT), Handler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    print("point relay: https on :%d, root=%s" % (PORT, ROOT), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
