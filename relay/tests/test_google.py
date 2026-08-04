"""Дорога к Google — чистая часть: адрес запроса и честный отказ. Сеть здесь не трогается."""
from __future__ import annotations

import pathlib
import sys
import urllib.parse

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from point_server.google import GoogleError, HttpGoogleIdentity, UnconfiguredGoogle  # noqa: E402


def test_адрес_входа_просит_только_имя_и_почту():
    identity = HttpGoogleIdentity("клиент", "секрет", "https://point.test/auth/callback")
    url = identity.authorize_url(state="СОСТОЯНИЕ", challenge="ВЫЗОВ")
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    assert query["scope"] == ["openid email profile"]
    assert query["response_type"] == ["code"]
    assert query["code_challenge_method"] == ["S256"]
    assert query["redirect_uri"] == ["https://point.test/auth/callback"]
    assert query["access_type"] == ["online"], "refresh-токен не нужен: Google нужен один раз"
    assert "секрет" not in url, "client_secret не покидает сервер даже в адресе"


def test_невнятный_id_token_не_проходит():
    identity = HttpGoogleIdentity("клиент", "секрет", "https://point.test/auth/callback")
    with pytest.raises(GoogleError):
        identity.verify("это не jwt")


def test_ненастроенный_вход_отказывает_а_не_молчит():
    identity = UnconfiguredGoogle()
    with pytest.raises(GoogleError):
        identity.authorize_url(state="s", challenge="c")
    with pytest.raises(GoogleError):
        identity.exchange(code="c", verifier="v")
