#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сверить боевой сервер с main (#1231).

Между «слито в main» и «работает у человека» не было ни одного сигнала: зелёный CI проверял
КОД В РЕПОЗИТОРИИ и ничего не говорил про боевую машину. Дважды это стоило человеку недель
старого поведения при закрытой карточке — приём файла отвечал 500 (#723), ответы на ошибки
оставались английскими (#1130), — и оба раза расхождение нашёл человек, а не прогон.

Приём тот же, что в release.yml для версии Point: объявленное сверяется с фактическим, и
расхождение роняет прогон. Ключа от боевой машины здесь нет и не нужно — сервер называет
выложенный код сам, в `/health`, а кладёт этот отпечаток `tools/deploy-server.sh`.

  python tools/check_server_deployed.py                      # боевой сервер против main
  python tools/check_server_deployed.py <адрес> <коммит>      # сверить названное
"""
from __future__ import annotations

import os
import subprocess
import sys
import urllib.request

HEALTH = "https://point.leerio.app/health"

# Файлы, которые кладёт выкладка. Отпечаток — коммит последней правки именно их, а не любой
# правки в репозитории: иначе красным становился бы каждый push, сервера не касавшийся, и
# человек привык бы к красному.
DEPLOYED_FILES = ("relay/point_server", "relay/requirements.txt", "relay/point-server-start.sh")

UNKNOWN = "неизвестен"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def deployed_code(answer: str) -> str:
    """Какой код называет сервер.

    Старый сервер отвечал двумя байтами «ok» и не называл ничего — это ровно то состояние,
    ради которого сверка и написана: на машине не то, что в main, и узнать это было неоткуда.
    """
    lines = answer.strip().splitlines()
    first = lines[0].strip() if lines else ""
    # Строгое «ok<пробел>»: и старое двухбайтовое «ok», и страница ошибки от чужого сервера
    # одинаково означают «код не назван», а не «назван как-то».
    if not first.startswith("ok "):
        return UNKNOWN
    return first[3:].strip() or UNKNOWN


def verdict(answer: str, expected: str) -> tuple[int, str]:
    """Что сказать человеку и с каким исходом."""
    if not answer.strip():
        return 1, "боевой сервер не ответил — сверить выложенный код нечем"
    code = deployed_code(answer)
    if code == expected:
        return 0, "сервер выложен из " + expected
    return 1, (
        "на сервере не тот код: выложен %s, серверные файлы правились в %s. "
        "Выложить — bash tools/deploy-server.sh" % (code, expected)
    )


def ask(url: str) -> str:
    """Ответ сервера. Молчание — тоже ответ, и он не считается выложенным кодом."""
    try:
        with urllib.request.urlopen(url, timeout=20) as answer:  # noqa: S310 - адрес наш
            return answer.read().decode("utf-8", "replace")
    except Exception:
        return ""


def commit_of_server_files() -> str:
    done = subprocess.run(
        ["git", "log", "-1", "--format=%H", "--", *DEPLOYED_FILES],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    return done.stdout.strip()


def main(argv: list[str]) -> int:
    url = argv[0] if argv else HEALTH
    expected = argv[1] if len(argv) > 1 else commit_of_server_files()
    if not expected:
        print(
            "::error::не удалось узнать, каким коммитом правились серверные файлы — "
            "нужна история репозитория (fetch-depth: 0)"
        )
        return 2
    outcome, said = verdict(ask(url), expected)
    print(("::error::" + said) if outcome else said)
    return outcome


if __name__ == "__main__":
    # Русские буквы не должны ронять сам прогон, когда вывод уходит в файл с чужой кодировкой.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main(sys.argv[1:]))
