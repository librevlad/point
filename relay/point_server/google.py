"""Вход по Google — за швом, как любой сторонний сервис.

`GoogleIdentity` — контракт (кто этот человек), `HttpGoogleIdentity` — настоящая дорога,
в тестах вместо неё стоит фейк. Ровно тот же приём, что у `LlmClient` и `ExternalEye` в
приложении: сеть за интерфейсом, тесты без сети.

Что Point просит у Google: только `openid email profile`. Токен доступа Google **не
сохраняется** — он нужен ровно один раз, узнать человека. Значит утечка нашей базы не даёт
доступа ни к чьей почте и диску.
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Protocol

AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs"
ISSUERS = ("https://accounts.google.com", "accounts.google.com")
SCOPE = "openid email profile"


class GoogleError(Exception):
    """Вход не состоялся. Текст — для журнала разработчика, человеку показывается страница."""


@dataclass(frozen=True)
class GoogleUser:
    sub: str
    email: str
    name: str


class GoogleIdentity(Protocol):
    def authorize_url(self, *, state: str, challenge: str) -> str: ...

    def exchange(self, *, code: str, verifier: str) -> GoogleUser: ...


class HttpGoogleIdentity:
    """Authorization Code + PKCE, обмен кода делает сервер — устройство в OAuth не участвует."""

    def __init__(self, client_id: str, client_secret: str, redirect_uri: str, timeout: int = 15):
        self._client_id = client_id
        self._client_secret = client_secret
        self._redirect_uri = redirect_uri
        self._timeout = timeout

    def authorize_url(self, *, state: str, challenge: str) -> str:
        params = {
            "client_id": self._client_id,
            "redirect_uri": self._redirect_uri,
            "response_type": "code",
            "scope": SCOPE,
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            # Refresh-токен не нужен: Google нужен один раз, узнать человека.
            "access_type": "online",
            "prompt": "select_account",
        }
        return AUTH_ENDPOINT + "?" + urllib.parse.urlencode(params)

    def exchange(self, *, code: str, verifier: str) -> GoogleUser:
        body = urllib.parse.urlencode(
            {
                "code": code,
                "client_id": self._client_id,
                "client_secret": self._client_secret,
                "redirect_uri": self._redirect_uri,
                "grant_type": "authorization_code",
                "code_verifier": verifier,
            }
        ).encode("ascii")
        request = urllib.request.Request(
            TOKEN_ENDPOINT,
            data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as e:  # сеть, 4xx от Google, мусор в ответе — одинаково «не вышло»
            raise GoogleError("обмен кода не удался: %s" % type(e).__name__) from e
        id_token = payload.get("id_token")
        if not id_token:
            raise GoogleError("Google не вернул id_token")
        return self.verify(id_token)

    def verify(self, id_token: str) -> GoogleUser:
        """Подпись RS256 проверяет библиотека.

        Своя реализация проверки подписи была бы худшим кодом в проекте, и это записано
        в проекте прямым текстом. Импорт ленивый: тесты этой дороги не касаются.
        """
        try:
            import jwt  # PyJWT
        except ImportError as e:  # pragma: no cover - в тестах фейк, здесь только прод
            raise GoogleError(
                "нет PyJWT — проверить подпись Google нечем (pip install -r requirements.txt)"
            ) from e
        try:
            key = jwt.PyJWKClient(JWKS_URI).get_signing_key_from_jwt(id_token)
            claims = jwt.decode(
                id_token,
                key.key,
                algorithms=["RS256"],
                audience=self._client_id,
                issuer=list(ISSUERS),
                options={"require": ["exp", "iat", "aud", "iss", "sub"]},
            )
        except Exception as e:
            raise GoogleError("подпись Google не проверилась: %s" % type(e).__name__) from e
        sub = str(claims.get("sub") or "")
        if not sub:
            raise GoogleError("в id_token нет sub")
        return GoogleUser(
            sub=sub, email=str(claims.get("email") or ""), name=str(claims.get("name") or "")
        )


class UnconfiguredGoogle:
    """Клиента Google ещё нет (это действие владельца, #469) — и сервер говорит это прямо.

    Не заглушка, которая молча пускает: без учётных данных вход невозможен, и человек видит
    честный отказ, а не бесконечное ожидание.
    """

    def authorize_url(self, *, state: str, challenge: str) -> str:
        raise GoogleError("вход по Google не настроен на сервере")

    def exchange(self, *, code: str, verifier: str) -> GoogleUser:
        raise GoogleError("вход по Google не настроен на сервере")
