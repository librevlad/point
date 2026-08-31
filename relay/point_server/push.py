"""Стук в устройство: «зайди, для тебя что-то есть» (#817).

Компьютер кладёт просьбу в свою папку и ждёт, пока человек откроет Point на телефоне.
Ждать можно до вечера — просьба не пропадёт, но и не случится. Стук снимает это ожидание.

Через Google идёт **только слово «зайди»**. Ни объекта, ни названия действия, ни имени
файла: сама просьба лежит на компьютере хозяина и туда телефон идёт сам. Решение владельца
13.08.2026: «исполнение + стук push-ом», при условии что содержимое мимо Google.

Своей реализации RS256 здесь нет — она уже есть в проверке входа Google, той же
библиотекой. Новых зависимостей стук не приносит.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

TOKEN_URL = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

#: Ключ живёт час; берём с запасом, чтобы не отправить стук с протухшим пропуском.
EARLY_S = 120


class Silent(Exception):
    """Стучать нечем: не задан ключ, нет токена устройства или Google отказал."""


@dataclass
class ServiceAccount:
    """Служебная учётка Firebase: ею сервер представляется Google."""

    project_id: str
    client_email: str
    private_key: str

    @staticmethod
    def load(path: str) -> "ServiceAccount":
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        missing = [k for k in ("project_id", "client_email", "private_key") if not raw.get(k)]
        if missing:
            raise Silent(f"в ключе Firebase нет полей: {', '.join(missing)}")
        return ServiceAccount(raw["project_id"], raw["client_email"], raw["private_key"])


@dataclass
class Knocker:
    """Отправитель стука. Пропуск Google берётся раз в час, а не на каждый стук."""

    account: ServiceAccount
    now: object = time.time
    _pass: str = ""
    _until: float = 0.0
    _open: object = field(default=urllib.request.urlopen)

    def access(self) -> str:
        now = float(self.now())
        if self._pass and now < self._until - EARLY_S:
            return self._pass

        import jwt  # лениво: тестам стука подпись не нужна

        claim = {
            "iss": self.account.client_email,
            "scope": SCOPE,
            "aud": TOKEN_URL,
            "iat": int(now),
            "exp": int(now) + 3600,
        }
        assertion = jwt.encode(claim, self.account.private_key, algorithm="RS256")
        body = urllib.parse.urlencode(
            {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}
        ).encode()
        req = urllib.request.Request(
            TOKEN_URL, data=body, headers={"Content-Type": "application/x-www-form-urlencoded"}
        )
        try:
            with self._open(req, timeout=10) as r:
                got = json.load(r)
        except urllib.error.URLError as e:
            raise Silent(f"Google не выдал пропуск: {e}") from e

        self._pass = got.get("access_token", "")
        if not self._pass:
            raise Silent("Google не выдал пропуск")
        self._until = now + float(got.get("expires_in", 3600))
        return self._pass

    def knock(self, token: str) -> bool:
        """Отдать письмо почте. `True` — почта его приняла, `False` — адреса больше нет.

        Принято почтой — это не «телефон проснулся» (#1108). Google отвечает согласием и
        когда телефон выключен, и когда приложение на нём остановлено человеком: такое
        приложение Android не поднимает ни на какое письмо. Что вышло из стука, видно не
        отсюда, а на самом компьютере: пришёл к нему телефон за просьбой или не пришёл.
        """
        if not token:
            raise Silent("у устройства нет адреса для стука")

        # Будящее письмо (#1108): только `data` и высокий приоритет. Так почта поднимает
        # закрытый Point, и он сам идёт за просьбой. Письмо с заголовком показал бы сам
        # Android — человек увидел бы строку, а приложение не проснулось бы; и заголовок
        # означал бы, что содержимое просьбы едет через Google.
        message = {
            "message": {
                "token": token,
                "data": {"knock": "outbox"},
                "android": {"priority": "high"},
            }
        }
        url = f"https://fcm.googleapis.com/v1/projects/{self.account.project_id}/messages:send"
        req = urllib.request.Request(
            url,
            data=json.dumps(message).encode(),
            headers={
                "Authorization": f"Bearer {self.access()}",
                "Content-Type": "application/json; charset=utf-8",
            },
        )
        try:
            with self._open(req, timeout=10) as r:
                r.read()
            return True
        except urllib.error.HTTPError as e:
            # Телефон переустановили или удалили приложение: адрес мёртв, и это не сбой.
            if e.code in (404, 403):
                return False
            raise Silent(f"Google не принял стук: {e.code}") from e
        except urllib.error.URLError as e:
            raise Silent(f"до Google не дозвониться: {e}") from e


def knocker(key_path: str) -> Knocker | None:
    """Собрать отправителя, если ключ задан. Без ключа Point работает — просто без стука."""
    if not key_path:
        return None
    return Knocker(ServiceAccount.load(key_path))
