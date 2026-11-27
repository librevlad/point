"""Предел тела запроса — по пришедшим байтам и до разбора (охота 27.11.2026).

Сервер проверял размер только у пробы AI и только по заголовку `Content-Length`. Тело
кусками (chunked) заголовка не несёт и проходило мимо: 12 МБ ушли к сервису AI. А FastAPI
читает и разбирает тело ДО проверки пропуска, и предела не было ни здесь, ни в Caddy:
аноним мог заставить сервер держать в памяти тело любого размера. Машина общая с JARVIS и
другими службами — память здесь не только наша.

Поэтому предел считается по байтам, которые на самом деле пришли: заголовок его не обманет.
Объявленное сразу больше предела отбивается раньше, чем прочитан первый кусок. Отказ —
исключение изнутри чтения: его отдаёт обычный обработчик отказов приложения, тем же голосом
и на языке человека.
"""
from __future__ import annotations

from typing import Callable


class BodyLimit:
    """ASGI-прослойка: [limit_for] — предел пути в байтах, [refusal] — что бросить сверх него."""

    def __init__(
        self,
        app,
        limit_for: Callable[[str, str], int],
        refusal: Callable[[str], Exception],
    ) -> None:
        self.app = app
        self.limit_for = limit_for
        self.refusal = refusal

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        limit = self.limit_for(scope.get("method", "GET"), path)
        declared = _declared_length(scope)
        seen = 0

        async def counted():
            nonlocal seen
            if declared is not None and declared > limit:
                raise self.refusal(path)
            message = await receive()
            if message.get("type") == "http.request":
                seen += len(message.get("body", b""))
                if seen > limit:
                    raise self.refusal(path)
            return message

        await self.app(scope, counted, send)


def _declared_length(scope) -> int | None:
    for name, value in scope.get("headers", []):
        if name == b"content-length":
            try:
                return int(value)
            except ValueError:
                return None
    return None
