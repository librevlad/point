"""Все запросы к базе — в одном файле, и это не аккуратность, а инвариант.

**Изоляция — форма запроса, а не проверка.** `user_id`, добытый из пропуска, входит в
каждый `WHERE`; запроса к чужим данным не существует как выражения, поэтому отдельной
«проверки прав» нет и забыть её негде. Единственное место, где владелец не подставляется,
а **выводится**, — `device_by_token`: там из пропуска и рождается `user_id`. Это отмечено
явно, и на это смотрит тест (`test_isolation.py::test_каждый_запрос_к_устройствам_несёт_владельца`).
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from . import ids


@dataclass(frozen=True)
class Caller:
    """Кто спрашивает: устройство и его хозяин. Ничего больше в запрос не попадает."""

    user_id: str
    device_id: str
    email: str
    name: str


# --- вход -----------------------------------------------------------------------------


def create_login(
    conn: sqlite3.Connection,
    *,
    login_id: str,
    claim_token: str,
    code: str,
    kind: str,
    name: str,
    key_agree: str,
    key_sign: str,
    now: int,
    ttl: int,
    handoff: bool = False,
) -> None:
    conn.execute(
        "INSERT INTO logins (id, claim_sha256, user_code, kind, name, key_agree, key_sign,"
        " created_at, expires_at, handoff) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (
            login_id,
            ids.sha256_hex(claim_token),
            code,
            kind,
            name,
            key_agree,
            key_sign,
            now,
            now + ttl,
            1 if handoff else 0,
        ),
    )


def login(conn: sqlite3.Connection, login_id: str, now: int) -> sqlite3.Row | None:
    row = conn.execute(
        "SELECT * FROM logins WHERE id = ? AND expires_at > ?", (login_id, now)
    ).fetchone()
    return row


def login_by_state(conn: sqlite3.Connection, state: str, now: int) -> sqlite3.Row | None:
    if not state:
        return None
    return conn.execute(
        "SELECT * FROM logins WHERE state = ? AND expires_at > ?", (state, now)
    ).fetchone()


def start_oauth(conn: sqlite3.Connection, login_id: str, state: str, verifier: str) -> None:
    """Человек нажал «Войти через Google» — только теперь заводится state и PKCE.

    `state` намеренно **не** равен `login_id`: `login_id` — адрес, по которому устройство
    забирает пропуск, и ему нечего делать в адресной строке Google.
    """
    conn.execute(
        "UPDATE logins SET state = ?, verifier = ? WHERE id = ?", (state, verifier, login_id)
    )


def finish_login(conn: sqlite3.Connection, login_id: str, user_id: str, now: int) -> None:
    conn.execute(
        "UPDATE logins SET user_id = ?, done_at = ?, state = NULL, verifier = '' WHERE id = ?",
        (user_id, now, login_id),
    )


def claim_login(conn: sqlite3.Connection, login_id: str, now: int) -> int:
    """Забрать вход можно один раз: второй опрос уже ничего не находит."""
    cur = conn.execute(
        "UPDATE logins SET claimed_at = ? WHERE id = ? AND claimed_at IS NULL", (now, login_id)
    )
    return cur.rowcount


def sweep_logins(conn: sqlite3.Connection, now: int) -> None:
    conn.execute("DELETE FROM logins WHERE expires_at <= ?", (now,))


# --- человек и его устройства ----------------------------------------------------------


def upsert_user(
    conn: sqlite3.Connection, *, google_sub: str, email: str, name: str, now: int
) -> str:
    """Человек узнаётся по `google_sub`, а не по почте: почта меняется, `sub` — нет."""
    row = conn.execute("SELECT id FROM users WHERE google_sub = ?", (google_sub,)).fetchone()
    if row:
        conn.execute(
            "UPDATE users SET email = ?, name = ? WHERE google_sub = ?", (email, name, google_sub)
        )
        return row["id"]
    user_id = ids.opaque(16)
    conn.execute(
        "INSERT INTO users (id, google_sub, email, name, created_at) VALUES (?,?,?,?,?)",
        (user_id, google_sub, email, name, now),
    )
    return user_id


def user(conn: sqlite3.Connection, user_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def add_device(
    conn: sqlite3.Connection,
    *,
    user_id: str,
    kind: str,
    name: str,
    key_agree: str,
    key_sign: str,
    now: int,
) -> str:
    device_id = ids.opaque(16)
    conn.execute(
        "INSERT INTO devices (id, user_id, kind, name, key_agree, key_sign, created_at, last_seen)"
        " VALUES (?,?,?,?,?,?,?,?)",
        (device_id, user_id, kind, name, key_agree, key_sign, now, now),
    )
    return device_id


def issue_token(conn: sqlite3.Connection, device_id: str, now: int) -> str:
    """Пропуск — 256 бит случайности; в базе от него остаётся только SHA-256.

    Не JWT: JWT нельзя отозвать, а отзыв («потерял телефон») — то самое свойство, ради
    которого всё делается.
    """
    token = ids.opaque(32)
    conn.execute(
        "INSERT INTO tokens (token_sha256, device_id, created_at, last_used_at) VALUES (?,?,?,?)",
        (ids.sha256_hex(token), device_id, now, now),
    )
    return token


def device_by_token(conn: sqlite3.Connection, token: str, now: int) -> Caller | None:
    """ЕДИНСТВЕННОЕ место, где владелец выводится, а не подставляется.

    Отсюда `user_id` уходит во все остальные запросы. Отозванное устройство отсюда не
    возвращается — значит следующий же его запрос получает 401.
    """
    if not token:
        return None
    row = conn.execute(
        "SELECT d.id AS device_id, d.user_id AS user_id, u.email AS email, u.name AS name"
        " FROM tokens t"
        " JOIN devices d ON d.id = t.device_id"
        " JOIN users u ON u.id = d.user_id"
        " WHERE t.token_sha256 = ? AND d.revoked_at IS NULL",
        (ids.sha256_hex(token),),
    ).fetchone()
    if not row:
        return None
    conn.execute(
        "UPDATE tokens SET last_used_at = ? WHERE token_sha256 = ?", (now, ids.sha256_hex(token))
    )
    conn.execute(
        "UPDATE devices SET last_seen = ? WHERE id = ? AND user_id = ?",
        (now, row["device_id"], row["user_id"]),
    )
    return Caller(
        user_id=row["user_id"],
        device_id=row["device_id"],
        email=row["email"] or "",
        name=row["name"] or "",
    )


def circle(conn: sqlite3.Connection, user_id: str) -> list[sqlite3.Row]:
    return list(
        conn.execute(
            "SELECT * FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at",
            (user_id,),
        )
    )


def device(conn: sqlite3.Connection, user_id: str, device_id: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM devices WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        (device_id, user_id),
    ).fetchone()


def update_device(
    conn: sqlite3.Connection,
    *,
    user_id: str,
    device_id: str,
    name: str | None,
    key_agree: str | None,
    key_sign: str | None,
) -> int:
    sets, args = [], []
    if name is not None:
        sets.append("name = ?")
        args.append(name)
    if key_agree is not None:
        sets.append("key_agree = ?")
        args.append(key_agree)
    if key_sign is not None:
        sets.append("key_sign = ?")
        args.append(key_sign)
    if not sets:
        return 0
    args += [device_id, user_id]
    cur = conn.execute(
        "UPDATE devices SET " + ", ".join(sets) + " WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        args,
    )
    return cur.rowcount


def revoke_device(conn: sqlite3.Connection, *, user_id: str, device_id: str, now: int) -> int:
    """Отключить может любое устройство круга — и себя тоже («Выйти»).

    Чужое устройство отсюда не отключается, и не потому что «нет прав», а потому что при
    чужом `user_id` строки просто нет.
    """
    cur = conn.execute(
        "UPDATE devices SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        (now, device_id, user_id),
    )
    if cur.rowcount:
        conn.execute(
            "DELETE FROM tokens WHERE device_id IN"
            " (SELECT id FROM devices WHERE id = ? AND user_id = ?)",
            (device_id, user_id),
        )
    return cur.rowcount


def delete_account(conn: sqlite3.Connection, user_id: str) -> None:
    """«Удалить всё моё» — немедленно, не дожидаясь суток."""
    conn.execute(
        "DELETE FROM tokens WHERE device_id IN (SELECT id FROM devices WHERE user_id = ?)",
        (user_id,),
    )
    conn.execute("DELETE FROM devices WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM logins WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
