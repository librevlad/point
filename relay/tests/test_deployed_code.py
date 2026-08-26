"""Сервер называет выложенный код, а сверка замечает расхождение (#1231).

Дважды между «слито в main» и «работает у человека» не было ни одного сигнала: приём файла
неделю отвечал 500 (#723), человеческие ответы на ошибки прожили недели английскими (#1130).
Оба раза расхождение нашёл человек, а не прогон.

Здесь проверяются обе половины пути: сервер называет свой код, и сверка роняет прогон, когда
названное не сходится с main. Сверка живёт в `tools/`, но проверяется здесь — другого набора
тестов, который CI гоняет для сервера, в проекте нет.
"""
from __future__ import annotations

import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "tools"))

import check_server_deployed as check  # noqa: E402
from point_server import app as app_mod  # noqa: E402

SOME_COMMIT = "8f21bb0a9c3d4e5f60718293a4b5c6d7e8f90123"
STALE_COMMIT = "0000000000000000000000000000000000000000"


@pytest.fixture
def deployed(tmp_path, monkeypatch):
    """Каталог, в который выложен код: отпечаток сервер ищет рядом с ним, а не у данных."""
    monkeypatch.setattr(app_mod, "CODE_DIR", str(tmp_path))
    return tmp_path


# --- сервер называет себя ------------------------------------------------------------------


def test_здоровье_называет_выложенный_код(point, deployed):
    (deployed / "deployed.txt").write_text(SOME_COMMIT + "\n", encoding="utf-8")

    assert point.client.get("/health").text == "ok " + SOME_COMMIT


def test_без_отпечатка_сервер_честно_говорит_что_кода_не_знает(point, deployed):
    # Так отвечает машина, на которую выкладывали руками: назвать код нечем, и молчать об этом
    # нельзя — иначе «ok» снова означало бы «всё в порядке» при неизвестно каком коде.
    assert point.client.get("/health").text == "ok неизвестен"


def test_отпечаток_с_мусором_не_растекается_по_ответу(point, deployed):
    (deployed / "deployed.txt").write_text(SOME_COMMIT + "\nстарая строка\n", encoding="utf-8")

    assert point.client.get("/health").text == "ok " + SOME_COMMIT


# --- сверка замечает расхождение -----------------------------------------------------------


def test_сверка_молчит_когда_на_сервере_тот_же_код():
    outcome, said = check.verdict("ok " + SOME_COMMIT, SOME_COMMIT)

    assert outcome == 0
    assert SOME_COMMIT in said


def test_сверка_роняет_прогон_и_называет_оба_кода():
    outcome, said = check.verdict("ok " + STALE_COMMIT, SOME_COMMIT)

    assert outcome == 1
    assert STALE_COMMIT in said and SOME_COMMIT in said
    assert "deploy-server.sh" in said


def test_сервер_со_старым_ответом_считается_невыложенным():
    # Ровно то состояние, ради которого сверка написана: сервер жив, отвечает «ok» и не
    # называет ничего.
    outcome, said = check.verdict("ok", SOME_COMMIT)

    assert outcome == 1
    assert check.UNKNOWN in said


def test_молчащий_сервер_не_считается_выложенным():
    outcome, said = check.verdict("", SOME_COMMIT)

    assert outcome == 1
    assert "не ответил" in said


def test_сверка_целиком_дёргает_адрес_и_возвращает_исход(tmp_path):
    health = tmp_path / "health.txt"
    health.write_text("ok " + SOME_COMMIT, encoding="utf-8")

    assert check.main([health.as_uri(), SOME_COMMIT]) == 0
    assert check.main([health.as_uri(), STALE_COMMIT]) == 1
