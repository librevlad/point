"""SQLite в режиме WAL: соединение и схема.

WAL — чтобы читатели не ждали писателя: сервер параллельно опрашивают все устройства всех
аккаунтов, а пишет он редко (вход, отзыв, отметка «на связи»).

Схема ровно та, что в проекте (`docs/superpowers/specs/2026-08-04-point-server.md`, раздел 2).
Ящики, ссылки и квоты (`mailbox`, `drops`, `inboxes`, `ai_usage`) в этот срез не входят —
они приезжают вместе со своими ручками (#476, #477), а не пустыми таблицами.
"""
from __future__ import annotations

import os
import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id          TEXT PRIMARY KEY,
  google_sub  TEXT NOT NULL UNIQUE,
  email       TEXT NOT NULL DEFAULT '',
  name        TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,
  name        TEXT NOT NULL DEFAULT '',
  key_agree   TEXT NOT NULL DEFAULT '',
  key_sign    TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL,
  revoked_at  INTEGER
);
CREATE INDEX IF NOT EXISTS devices_by_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS tokens (
  token_sha256 TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_by_device ON tokens(device_id);

-- Незавершённый вход. Живёт пять минут, забирается один раз и умирает.
-- Пропуска здесь нет: он рождается в момент, когда устройство его забирает.
CREATE TABLE IF NOT EXISTS logins (
  id           TEXT PRIMARY KEY,
  claim_sha256 TEXT NOT NULL,
  user_code    TEXT NOT NULL,
  kind         TEXT NOT NULL,
  name         TEXT NOT NULL DEFAULT '',
  key_agree    TEXT NOT NULL DEFAULT '',
  key_sign     TEXT NOT NULL DEFAULT '',
  state        TEXT UNIQUE,
  verifier     TEXT NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  user_id      TEXT,
  done_at      INTEGER,
  claimed_at   INTEGER
);
CREATE INDEX IF NOT EXISTS logins_by_state ON logins(state);
"""

#: Колонки, добавленные после первой версии схемы. `CREATE TABLE IF NOT EXISTS` их не довезёт:
#: таблица уже есть, и новая колонка молча не появится — сервер начнёт падать на живой базе.
ADDED_COLUMNS = {
    # Вход начат там же, где откроется браузер (#561): человеку нечего сверять глазами, и
    # подтверждать он будет свой собственный вход в приложении, которое сам же открыл.
    "logins": [("handoff", "INTEGER NOT NULL DEFAULT 0")],
}


def connect(path: str) -> sqlite3.Connection:
    """Соединение под запрос: автокоммит, WAL, каскады включены, ожидание блокировки 10 с."""
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    # `check_same_thread=False`: соединение живёт ровно один запрос, но FastAPI разбирает
    # зависимости в одном потоке, а исполняет обработчик в другом. Делить соединение между
    # запросами при этом никто не начинает — на каждый своё.
    conn = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=10000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init(path: str) -> None:
    conn = connect(path)
    try:
        conn.executescript(SCHEMA)
        migrate(conn)
    finally:
        conn.close()


def migrate(conn: sqlite3.Connection) -> None:
    """Довезти колонки, которых нет в уже существующей базе.

    SQLite не умеет `ADD COLUMN IF NOT EXISTS`, поэтому наличие проверяется списком колонок.
    Дешевле и честнее номеров версий: список выше и есть описание того, чего не хватает.
    """
    for table, columns in ADDED_COLUMNS.items():
        have = {r["name"] for r in conn.execute(f"PRAGMA table_info({table})")}
        for name, decl in columns:
            if name not in have:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN {name} {decl}")
