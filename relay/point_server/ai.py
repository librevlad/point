"""AI без своего ключа: пробные действия через сервер Point (#477).

Владелец 27.11.2026, на модель «Pro за AI без ключа» и первым шагом — пробную квоту:
«Да, так и делаем». Это заменяет решение 05.08.2026 «AI на сервере нет и не будет» (#82).

Дверь зрячая, и так и названа: чтобы передать объект модели, сервер его видит. Поэтому:

- наверху только сервисы, которые письменно обещают не учиться на присланном. Список —
  здесь, в коде, а не в окружении: обещание «не учится» Point даёт за этих двоих (условия
  перечитаны 26.11.2026, каталог `AiProviders.kt`), и подменить их настройкой нельзя;
- тело запроса сервер не хранит и не пишет в журнал — только считает действия;
- считается действие, на которое сервис ответил: чужой отказ пробу не съедает.

Ответ модели отдаётся как есть, в формате OpenAI: телефон уже умеет такого исполнителя
(`OpenAiCompatibleClient`), и нового клиента на нём не появилось.
"""
from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable

# Без браузерного User-Agent часть сервисов молча отвечает отказом (tools/vision/freeprobe.py).
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# Предел ответа: проба — это прочитать, понять, перевести, а не написать книгу.
MAX_TOKENS = 4096
TIMEOUT_SECONDS = 60


@dataclass(frozen=True)
class Upstream:
    name: str
    url: str
    key: str
    model: str


def upstreams(groq_key: str, cloudflare_key: str, cloudflare_account: str) -> list[Upstream]:
    """Кто отвечает на пробу, по порядку. Оба видят картинку: накладную прочитали 26.11.2026."""
    out: list[Upstream] = []
    if groq_key:
        # Groq: «not permitted to use Inputs or Outputs for training» — без деления на уровни.
        out.append(Upstream("groq", "https://api.groq.com/openai/v1/chat/completions",
                            groq_key, "qwen/qwen3.8-27b"))
    if cloudflare_key and cloudflare_account:
        # Cloudflare Workers AI: «does not use Customer Content … to train AI models».
        out.append(Upstream(
            "cloudflare",
            "https://api.cloudflare.com/client/v4/accounts/%s/ai/v1/chat/completions" % cloudflare_account,
            cloudflare_key,
            "@cf/mistralai/mistral-small-3.1-24b-instruct",
        ))
    return out


def answered(body: bytes) -> bool:
    """Ответ модели голосом OpenAI: JSON с непустым `choices`."""
    try:
        data = json.loads(body)
    except ValueError:
        return False
    choices = data.get("choices") if isinstance(data, dict) else None
    return isinstance(choices, list) and len(choices) > 0


class AiGateway:
    """Шов «спросить модель»: на сервере — HTTP к сервисам, в тестах — подмена."""

    @property
    def available(self) -> bool:
        return False

    def ask(self, messages: list, max_tokens: int) -> tuple[int, bytes, str]:
        """(код, ответ, кто ответил). 200 — ответ сервиса как есть."""
        return 503, b"", ""


class HttpAiGateway(AiGateway):
    def __init__(self, ups: list[Upstream], opener: Callable = urllib.request.urlopen):
        self.ups = ups
        self.opener = opener

    @property
    def available(self) -> bool:
        return bool(self.ups)

    def ask(self, messages: list, max_tokens: int) -> tuple[int, bytes, str]:
        last = 503
        for up in self.ups:
            body = json.dumps({
                "model": up.model,
                "messages": messages,
                "max_tokens": max_tokens,
            }).encode("utf-8")
            req = urllib.request.Request(up.url, data=body, method="POST", headers={
                "Authorization": "Bearer " + up.key,
                "Content-Type": "application/json",
                "User-Agent": UA,
            })
            try:
                with self.opener(req, timeout=TIMEOUT_SECONDS) as resp:
                    reply = resp.read()
                # 2xx — ещё не ответ модели: пустое тело, страница или ошибка в обёртке 200
                # списали бы пробу за то, что телефон не прочтёт. Такое — повод спросить
                # следующего, как любой отказ (охота 27.11.2026, второе мнение Codex).
                if answered(reply):
                    return 200, reply, up.name
                last = 502
            except urllib.error.HTTPError as e:
                # Любой отказ — повод спросить следующего: у другого сервиса другие пределы
                # и другая модель. Тело чужого отказа наружу не идёт: в нём может быть эхо запроса.
                last = e.code
            except (urllib.error.URLError, socket.timeout, TimeoutError, OSError):
                last = 504
        return last, b"", ""
