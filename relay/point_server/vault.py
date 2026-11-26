"""Настройки аккаунта на диске сервера — только зашифрованными.

До 26.11.2026 настройки ездили запечатанными под ключи устройств круга (#610), и сервер
их прочесть не мог. Новое устройство открыть такой конверт тоже не могло, пока кто-то из
старых его не перепечатает, — и телефон, вошедший в аккаунт, видел один ключ из двенадцати.
Владелец: «это кошмар пользователя… оно должно зависеть только от аккаунта, не от круга»,
и из вариантов — «Сервер хранит ключи сам».

Сервер настройки читает: иначе отдать их новому устройству нельзя. Но в базе они лежат
зашифрованными ключом, который живёт отдельным файлом рядом со службой. Копия базы — для
разбора, резервная, случайная — ключей человека не содержит.

Ключ сервер создаёт сам при первом запуске: через руки человека или ИИ он не проходит.
Пропал ключ — настройки читаются как «их нет», и устройства кладут свои заново; молча
заменить ключ другим нельзя, поэтому испорченный файл ключа останавливает запуск.
"""
from __future__ import annotations

import base64
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

KEY_BYTES = 32
NONCE_BYTES = 12


def load_or_create_key(path: str) -> bytes:
    """Ключ из файла; файла нет — создаётся новый, читать его может только служба."""
    try:
        with open(path, "rb") as f:
            return _parse(f.read(), path)
    except FileNotFoundError:
        pass
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    key = AESGCM.generate_key(bit_length=KEY_BYTES * 8)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        # Два процесса поднимались одновременно — берётся ключ того, кто успел первым.
        with open(path, "rb") as f:
            return _parse(f.read(), path)
    with os.fdopen(fd, "wb") as f:
        f.write(base64.b64encode(key) + b"\n")
    return key


def _parse(raw: bytes, path: str) -> bytes:
    try:
        key = base64.b64decode(raw.strip(), validate=True)
    except ValueError:
        key = b""
    if len(key) != KEY_BYTES:
        raise RuntimeError(f"Файл ключа настроек {path} испорчен: сервер не заменит его сам.")
    return key


class SettingsVault:
    """AES-GCM; строка привязана к своему человеку: перенесённая в чужую строку не откроется."""

    def __init__(self, key: bytes):
        self._aead = AESGCM(key)

    def seal(self, user_id: str, text: str) -> str:
        nonce = os.urandom(NONCE_BYTES)
        body = self._aead.encrypt(nonce, text.encode("utf-8"), user_id.encode("utf-8"))
        return base64.b64encode(nonce + body).decode("ascii")

    def open(self, user_id: str, sealed: str) -> str | None:
        """`None` — прочесть нельзя (другой ключ, чужая строка, порча): для человека это «пусто»."""
        try:
            raw = base64.b64decode(sealed, validate=True)
            text = self._aead.decrypt(raw[:NONCE_BYTES], raw[NONCE_BYTES:], user_id.encode("utf-8"))
        except Exception:  # noqa: BLE001 — любая неудача значит одно: прочесть нечего
            return None
        return text.decode("utf-8")
