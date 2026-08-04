"""Сервер Point: аккаунты, круг устройств, изоляция (#471).

Проект целиком — `docs/superpowers/specs/2026-08-04-point-server.md`.

Граница, которая не двигается: **аккаунт даёт право положить и забрать, но не право
прочитать.** Этот срез не возит ни одного байта содержимого вовсе — он знает только, чьи
устройства. Слепой почтовый ящик (`relay/relay.py`) пока живёт рядом и переезжает под
аккаунты отдельным срезом (#476).
"""

__all__ = ["create_app"]


def create_app(*args, **kwargs):  # pragma: no cover - тонкая обёртка над app.create_app
    from .app import create_app as _create_app

    return _create_app(*args, **kwargs)
