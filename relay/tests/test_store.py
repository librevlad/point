"""База: WAL, хранение пропусков, уборка просроченных входов, коды."""
from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from point_server import db, ids, store  # noqa: E402


def open_db(tmp_path):
    path = str(tmp_path / "point.db")
    db.init(path)
    return db.connect(path)


def test_база_в_режиме_wal(tmp_path):
    conn = open_db(tmp_path)
    assert conn.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"


def test_пропуск_хранится_только_отпечатком(tmp_path):
    conn = open_db(tmp_path)
    user_id = store.upsert_user(conn, google_sub="s", email="e", name="n", now=1)
    device_id = store.add_device(
        conn, user_id=user_id, kind="PHONE", name="", key_agree="", key_sign="", now=1
    )
    token = store.issue_token(conn, device_id, 1)
    stored = conn.execute("SELECT token_sha256 FROM tokens").fetchone()[0]
    assert stored == ids.sha256_hex(token) and stored != token
    assert store.device_by_token(conn, token, 2).device_id == device_id
    assert store.device_by_token(conn, "не тот", 2) is None


def test_отзыв_убивает_пропуск_а_не_прячет_его(tmp_path):
    conn = open_db(tmp_path)
    user_id = store.upsert_user(conn, google_sub="s", email="e", name="n", now=1)
    device_id = store.add_device(
        conn, user_id=user_id, kind="PHONE", name="", key_agree="", key_sign="", now=1
    )
    token = store.issue_token(conn, device_id, 1)
    assert store.revoke_device(conn, user_id=user_id, device_id=device_id, now=5) == 1
    assert conn.execute("SELECT COUNT(*) FROM tokens").fetchone()[0] == 0
    assert store.device_by_token(conn, token, 6) is None


def test_один_google_sub_один_человек(tmp_path):
    conn = open_db(tmp_path)
    first = store.upsert_user(conn, google_sub="s", email="старая@x", name="A", now=1)
    second = store.upsert_user(conn, google_sub="s", email="новая@x", name="Б", now=2)
    assert first == second
    assert conn.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 1
    assert store.user(conn, first)["email"] == "новая@x"


def test_просроченные_входы_убираются_сами(tmp_path):
    conn = open_db(tmp_path)
    store.create_login(
        conn,
        login_id="a",
        claim_token="t",
        code="K7-42Q",
        kind="PHONE",
        name="",
        key_agree="",
        key_sign="",
        now=100,
        ttl=300,
    )
    assert store.login(conn, "a", 200) is not None
    assert store.login(conn, "a", 500) is None, "просроченный вход не находится"
    store.sweep_logins(conn, 500)
    assert conn.execute("SELECT COUNT(*) FROM logins").fetchone()[0] == 0


def test_код_человека_читается_глазами(tmp_path):
    code = ids.user_code()
    assert len(code) == 6 and code[2] == "-"
    assert not set(code) & set("O0I1lS5B8")


def test_код_устройства_повторяем_и_зависит_от_ключей():
    a = ids.device_code("AGREE", "SIGN")
    assert a == ids.device_code("AGREE", "SIGN")
    assert a != ids.device_code("AGREE", "ДРУГОЙ")
    assert ids.device_code("", "") == ""
