"""Сервер отвечает на ошибки валидации по-русски и не возвращает присланное эхом (#1130).

Стандартный 422 FastAPI — технический английский с `loc`/`ctx` и копией присланного ввода
(`input`). Для сервера, который не возит ни байта содержимого, эхо присланного в теле
ошибки — дефект, а не деталь формата.
"""
from __future__ import annotations


def test_слишком_длинное_имя_отвечает_по_русски_и_без_эха(point):
    marker = "х" * 100_000
    response = point.client.post("/auth/start", json={"kind": "PHONE", "name": marker})
    assert response.status_code == 422
    body = response.json()

    # Тот же формат, что у своих проверок: только error и message, по-русски.
    assert set(body) == {"error", "message"}
    assert body["error"] == "bad_request"
    assert "проверьте" in body["message"]

    # И ни присланной строки, ни технических полей стандартного ответа.
    text = response.text
    assert marker[:100] not in text
    for leak in ("loc", "ctx", "input", "string_too_long"):
        assert leak not in text


def test_битый_json_отвечает_тем_же_форматом(point):
    response = point.client.post(
        "/auth/start",
        content=b'{"kind": "PHONE", "name": ',
        headers={"Content-Type": "application/json"},
    )
    assert response.status_code == 422
    body = response.json()
    assert set(body) == {"error", "message"}
    assert body["error"] == "bad_json"
    assert "читается" in body["message"]
    for leak in ("loc", "ctx", "input", "json_invalid", "PHONE"):
        assert leak not in response.text
