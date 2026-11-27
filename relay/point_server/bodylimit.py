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

Тело, которое замерло посреди передачи, тоже обрывается: ни uvicorn, ни Caddy медленное тело
сами не обрывают, и замершая отправка держала бы место и соединение сколько угодно (проверка
независимыми рецензентами). Срок тишины действует, только пока тело ещё идёт: ответ, который
сервер отдаёт долго, и долгое ожидание письма им не задеваются.
"""
from __future__ import annotations

import asyncio
from typing import Callable


class BodyLimit:
    """ASGI-прослойка: [limit_for] — предел пути в байтах, [refusal] — что бросить сверх него,
    [stalled] — что бросить, если тело молчит дольше [idle_seconds]."""

    def __init__(
        self,
        app,
        limit_for: Callable[[str, str], int],
        refusal: Callable[[str], Exception],
        stalled: Callable[[str], Exception] | None = None,
        idle_seconds: float = 300.0,
        max_seconds: float | None = None,
    ) -> None:
        self.app = app
        self.limit_for = limit_for
        self.refusal = refusal
        self.stalled = stalled
        self.idle_seconds = idle_seconds
        self.max_seconds = max_seconds

    async def __call__(self, scope, receive, send):
        # Тела у GET и HEAD нет: их чтение — только ожидание обрыва связи во время долгой
        # отдачи, и отказ там сорвал бы уже начатую отдачу файла (проверка рецензентами).
        if scope["type"] != "http" or scope.get("method") in ("GET", "HEAD"):
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        limit = self.limit_for(scope.get("method", "GET"), path)
        declared = _declared_length(scope)
        loop = asyncio.get_running_loop()
        started = loop.time()
        seen = 0
        body_done = False

        async def counted():
            nonlocal seen, body_done
            if body_done:
                return await receive()
            if declared is not None and declared > limit:
                raise self.refusal(path)
            if self.stalled is None:
                message = await receive()
            else:
                # Тишина — не дольше [idle_seconds]. Общего срока на всё тело по умолчанию нет:
                # медленная, но живая отправка со слабой мобильной связи не должна обрываться
                # на полпути; замершие держит в узде предел отправок на человека.
                budget = self.idle_seconds
                if self.max_seconds is not None:
                    budget = min(budget, self.max_seconds - (loop.time() - started))
                if budget <= 0:
                    raise self.stalled(path)
                try:
                    message = await asyncio.wait_for(receive(), budget)
                except asyncio.TimeoutError:
                    raise self.stalled(path) from None
            if message.get("type") == "http.request":
                seen += len(message.get("body", b""))
                if seen > limit:
                    raise self.refusal(path)
                if not message.get("more_body", False):
                    body_done = True
            else:
                body_done = True
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


def declared_length(headers) -> int | None:
    """Объявленный размер тела из заголовков Starlette — или `None`, если его нет или он не число."""
    value = headers.get("content-length")
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None
