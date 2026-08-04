"""Случайность, отпечатки и человекочитаемые коды.

Всё, что здесь есть, — стандартная библиотека: `secrets` для случайности, `hashlib` для
SHA-256. Своих криптографических конструкций сервер не заводит ни одной.
"""
from __future__ import annotations

import base64
import hashlib
import secrets

# Алфавит без похожих знаков: человек сверяет код глазами с экраном устройства, и «0/O»,
# «1/I/l» превратили бы сверку в лотерею.
CODE_ALPHABET = "ACDEFGHJKLMNPQRTUVWXY3479"


def opaque(nbytes: int = 32) -> str:
    """256 бит случайности в base64url без выравнивания — id входа, пропуск, адрес."""
    return base64.urlsafe_b64encode(secrets.token_bytes(nbytes)).decode("ascii").rstrip("=")


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def user_code() -> str:
    """Код вида «K7-42Q»: он не пропуск, а способ не подтвердить чужой вход."""
    pick = lambda n: "".join(secrets.choice(CODE_ALPHABET) for _ in range(n))
    return pick(2) + "-" + pick(3)


def device_code(key_agree: str, key_sign: str) -> str:
    """Восемь знаков из отпечатка открытых ключей устройства (проект, раздел 4).

    Смысл — дать человеку способ сверить глазами, что ключ у собеседника тот самый. Ключей
    ещё нет (они приходят срезом #474) — тогда и кода нет, а не «код ни от чего».
    """
    if not key_agree and not key_sign:
        return ""
    digest = hashlib.sha256(("%s\n%s" % (key_agree, key_sign)).encode("utf-8")).digest()
    n = int.from_bytes(digest[:8], "big")
    out = []
    for _ in range(8):
        out.append(CODE_ALPHABET[n % len(CODE_ALPHABET)])
        n //= len(CODE_ALPHABET)
    return "".join(out[:4]) + "-" + "".join(out[4:])
