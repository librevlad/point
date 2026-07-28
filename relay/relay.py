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

Endpoints (all but /health require the X-Point-App secret):
  GET  /health                     → 200 "ok"
  POST /mbx/<id>        body=blob   → 200, X-Blob-Id header (store ciphertext; ≤50 MB)
  GET  /mbx/<id>?wait=N            → 200 oldest blob (+X-Blob-Id) / 204 after N s long-poll
  POST /mbx/<id>/ack   X-Blob-Id   → 200 (delete; repeat ack is still 200)
"""
import http.server
import os
import socketserver
import ssl
import threading
import time
import uuid

ROOT = os.path.expanduser(os.environ.get("POINT_RELAY_ROOT", "~/point-relay"))
MBX = os.path.join(ROOT, "mbx")
SECRET = os.environ.get("POINT_RELAY_SECRET", "")
PORT = int(os.environ.get("POINT_RELAY_PORT", "8443"))
CERT = os.path.join(ROOT, "cert.pem")
KEY = os.path.join(ROOT, "key.pem")
MAX_BLOB = 50 * 1024 * 1024
TTL_SECONDS = 24 * 3600
WAIT_MAX = 30

os.makedirs(MBX, exist_ok=True)


def mailbox_dir(mid):
    """A mailbox id is base64url from the app; keep only its safe chars as the directory name."""
    safe = "".join(c for c in (mid or "") if c.isalnum() or c in "-_")
    return os.path.join(MBX, safe) if safe else None


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
        if not self.authed():
            return self.reply(401, b"nope")
        p = self.parts()
        if len(p) == 2 and p[0] == "mbx":
            return self.pull(mailbox_dir(p[1]))
        self.reply(404, b"")

    def do_POST(self):
        if not self.authed():
            return self.reply(401, b"nope")
        p = self.parts()
        if len(p) == 2 and p[0] == "mbx":
            return self.push(mailbox_dir(p[1]))
        if len(p) == 3 and p[0] == "mbx" and p[2] == "ack":
            return self.ack(mailbox_dir(p[1]))
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
    while True:
        cutoff = time.time() - TTL_SECONDS
        for root, _dirs, files in os.walk(MBX):
            for fn in files:
                path = os.path.join(root, fn)
                try:
                    if os.path.getmtime(path) < cutoff:
                        os.remove(path)
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
