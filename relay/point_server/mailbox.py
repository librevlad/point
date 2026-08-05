"""Ящики, ссылки и приём файлов — под аккаунтом (#476).

Переезд с релея. У релея адрес ящика был **и адресом, и пропуском**: кто знает адрес, тот и
хозяин. С аккаунтами пропуск — токен устройства, и адрес освобождается от второй работы:
`user_id` берётся из пропуска, а не из пути, и в каждый запрос входит владельцем.

**Три ручки остаются открытыми для чужого браузера** — забрать файл по ссылке, страница приёма и
сама отправка файла. У человека, которому вы дали ссылку, пропуска нет и быть не может; защита
там другая — неугадываемый адрес (160 бит) и срок жизни в сутки. Это названное исключение из
слепоты: **байты, отданные по ссылке, сервер видит**, в отличие от почты между вашими
устройствами. Так было и на релее; переезд ничего здесь не меняет и не прячет.

**Переполнение — честный отказ.** Молча вытеснять чужое старое ради нового нельзя: это та же
проглоченная ошибка, только на диске.
"""
from __future__ import annotations

import os
import shutil
import time
import uuid

# --- пределы (#476) ---------------------------------------------------------------------
MAX_BLOB = 50 * 1024 * 1024          # один файл
MAX_MAILBOX_BYTES = 300 * 1024 * 1024  # почта всех устройств человека
MAX_DROPS = 20                        # активных ссылок «Дать ссылку»
MAX_INBOXES = 5                       # открытых ящиков приёма
MAX_INBOX_FILES = 20                  # файлов в одном ящике приёма
MAX_TOTAL_BYTES = 500 * 1024 * 1024   # всё, что человек занимает на диске
TTL_SECONDS = 24 * 3600               # всё живёт сутки


class Full(Exception):
    """Место кончилось. Текст говорит человеку, что делать, а не «quota exceeded»."""


def _dir(root: str, *parts: str) -> str:
    p = os.path.join(root, *parts)
    os.makedirs(p, exist_ok=True)
    return p


def _size(path: str) -> int:
    total = 0
    for base, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(base, f))
            except OSError:
                pass
    return total


def user_root(root: str, user_id: str) -> str:
    """Всё, что принадлежит человеку, лежит под одним каталогом — и удаляется одним движением."""
    return _dir(root, "u", user_id)


def used_bytes(root: str, user_id: str) -> int:
    return _size(user_root(root, user_id))


def _guard_total(root: str, user_id: str, incoming: int) -> None:
    if incoming > MAX_BLOB:
        raise Full("Файл больше 50 МБ — столько сервер Point не берёт")
    if used_bytes(root, user_id) + incoming > MAX_TOTAL_BYTES:
        raise Full("На сервере кончилось место — заберите то, что уже отправлено")


# --- почта между своими устройствами ----------------------------------------------------


def mailbox_dir(root: str, user_id: str, device_id: str) -> str:
    return _dir(user_root(root, user_id), "mbx", device_id)


def push(root: str, user_id: str, device_id: str, data: bytes) -> str:
    """Положить письмо в ящик своего же устройства. Содержимое запечатано устройствами."""
    _guard_total(root, user_id, len(data))
    box = mailbox_dir(root, user_id, device_id)
    if _size(_dir(user_root(root, user_id), "mbx")) + len(data) > MAX_MAILBOX_BYTES:
        raise Full("Почта между вашими устройствами переполнена — заберите отправленное")
    bid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
    tmp = os.path.join(box, bid + ".part")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, os.path.join(box, bid + ".bin"))
    return bid


def pull(root: str, user_id: str, device_id: str) -> tuple[str, bytes] | None:
    """Забрать самое старое письмо, не удаляя: удалит `ack` — иначе потеря на разрыве связи."""
    box = mailbox_dir(root, user_id, device_id)
    names = sorted(n for n in os.listdir(box) if n.endswith(".bin"))
    if not names:
        return None
    with open(os.path.join(box, names[0]), "rb") as f:
        return names[0][:-4], f.read()


def ack(root: str, user_id: str, device_id: str, blob_id: str) -> bool:
    safe = "".join(c for c in blob_id if c.isalnum() or c in "-_")[:64]
    path = os.path.join(mailbox_dir(root, user_id, device_id), safe + ".bin")
    if os.path.isfile(path):
        os.remove(path)
        return True
    return False


# --- «Дать ссылку»: файл забирает чужой человек ------------------------------------------


def drops_dir(root: str, user_id: str) -> str:
    return _dir(user_root(root, user_id), "d")


def drop_put(root: str, user_id: str, data: bytes, name: str, mime: str) -> str:
    _guard_total(root, user_id, len(data))
    box_root = drops_dir(root, user_id)
    if len(os.listdir(box_root)) >= MAX_DROPS:
        raise Full("Больше 20 живых ссылок сразу не бывает — дождитесь, пока старые истекут")
    did = uuid.uuid4().hex + uuid.uuid4().hex[:8]  # 160 бит: ссылку не перебрать
    box = _dir(box_root, did)
    with open(os.path.join(box, "meta"), "w", encoding="utf-8") as f:
        f.write((name or "file").replace("\n", " ") + "\n" + (mime or "application/octet-stream").replace("\n", " "))
    tmp = os.path.join(box, "blob.part")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, os.path.join(box, "blob.bin"))
    return did


def drop_find(root: str, drop_id: str) -> tuple[str, str, str] | None:
    """Найти файл по ссылке — БЕЗ владельца: у чужого браузера пропуска нет, адрес и есть пропуск."""
    safe = "".join(c for c in drop_id if c.isalnum())[:80]
    if not safe:
        return None
    users = os.path.join(root, "u")
    if not os.path.isdir(users):
        return None
    for uid in os.listdir(users):
        box = os.path.join(users, uid, "d", safe)
        blob = os.path.join(box, "blob.bin")
        if os.path.isfile(blob):
            name, mime = "file", "application/octet-stream"
            try:
                with open(os.path.join(box, "meta"), encoding="utf-8") as f:
                    lines = f.read().split("\n")
                    name = lines[0] or name
                    mime = (lines[1] if len(lines) > 1 else "") or mime
            except OSError:
                pass
            return blob, name, mime
    return None


# --- «Принять файл»: чужой человек кладёт файл вам ---------------------------------------


def inboxes_dir(root: str, user_id: str) -> str:
    return _dir(user_root(root, user_id), "i")


def inbox_open(root: str, user_id: str) -> str:
    box_root = inboxes_dir(root, user_id)
    if len(os.listdir(box_root)) >= MAX_INBOXES:
        raise Full("Больше 5 открытых ящиков приёма сразу не бывает")
    box_id = uuid.uuid4().hex + uuid.uuid4().hex[:8]
    _dir(box_root, box_id)
    return box_id


def inbox_find(root: str, box_id: str) -> str | None:
    """Открытый ящик по адресу — тоже без владельца: страницу открывает чужой браузер."""
    safe = "".join(c for c in box_id if c.isalnum())[:80]
    if not safe:
        return None
    users = os.path.join(root, "u")
    if not os.path.isdir(users):
        return None
    for uid in os.listdir(users):
        box = os.path.join(users, uid, "i", safe)
        if os.path.isdir(box):
            return box
    return None


def inbox_accept(box: str, data: bytes, name: str, mime: str) -> None:
    if len(data) > MAX_BLOB:
        raise Full("Файл больше 50 МБ — столько сервер Point не берёт")
    if len([n for n in os.listdir(box) if n.endswith(".bin")]) >= MAX_INBOX_FILES:
        raise Full("В этот ящик больше не помещается — заберите присланное")
    fid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
    with open(os.path.join(box, fid + ".meta"), "w", encoding="utf-8") as f:
        f.write((name or "file").replace("\n", " ") + "\n" + (mime or "application/octet-stream").replace("\n", " "))
    tmp = os.path.join(box, fid + ".part")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, os.path.join(box, fid + ".bin"))


def inbox_ack(root: str, user_id: str, box_id: str, file_id: str) -> bool:
    """Забранное подтверждается отдельно — тем же приёмом, что и почта устройства.

    Без подтверждения тот же файл приезжал бы на каждом круге ожидания; удалять его прямо в
    выдаче тоже нельзя — на разрыве связи он пропал бы молча, а прислал его чужой человек и
    прислать заново не сможет.
    """
    safe_box = "".join(c for c in box_id if c.isalnum())[:80]
    safe_file = "".join(c for c in file_id if c.isalnum() or c in "-_")[:64]
    box = os.path.join(inboxes_dir(root, user_id), safe_box)
    removed = False
    for suffix in (".bin", ".meta"):
        path = os.path.join(box, safe_file + suffix)
        if os.path.isfile(path):
            os.remove(path)
            removed = True
    return removed


def inbox_take(root: str, user_id: str, box_id: str) -> tuple[str, bytes, str, str] | None:
    """Забрать присланное — уже под пропуском: ящик свой, значит и владелец подставляется."""
    safe = "".join(c for c in box_id if c.isalnum())[:80]
    box = os.path.join(inboxes_dir(root, user_id), safe)
    if not os.path.isdir(box):
        return None
    names = sorted(n for n in os.listdir(box) if n.endswith(".bin"))
    if not names:
        return None
    fid = names[0][:-4]
    name, mime = "file", "application/octet-stream"
    try:
        with open(os.path.join(box, fid + ".meta"), encoding="utf-8") as f:
            lines = f.read().split("\n")
            name = lines[0] or name
            mime = (lines[1] if len(lines) > 1 else "") or mime
    except OSError:
        pass
    with open(os.path.join(box, fid + ".bin"), "rb") as f:
        return fid, f.read(), name, mime


# --- уборка ------------------------------------------------------------------------------


def forget_user(root: str, user_id: str) -> None:
    """«Удалить всё моё» — немедленно, не дожидаясь суток."""
    shutil.rmtree(user_root(root, user_id), ignore_errors=True)


def forget_device(root: str, user_id: str, device_id: str) -> None:
    """«Выйти» на устройстве стирает его ящик: чужой почте там больше не место."""
    shutil.rmtree(mailbox_dir(root, user_id, device_id), ignore_errors=True)


def sweep(root: str, now: float | None = None) -> int:
    """Всё старше суток исчезает само. Возвращает, сколько каталогов убрано."""
    now = time.time() if now is None else now
    cutoff = now - TTL_SECONDS
    removed = 0
    users = os.path.join(root, "u")
    if not os.path.isdir(users):
        return 0
    for uid in os.listdir(users):
        for kind in ("d", "i"):
            base = os.path.join(users, uid, kind)
            if not os.path.isdir(base):
                continue
            for box in os.listdir(base):
                p = os.path.join(base, box)
                try:
                    if os.path.getmtime(p) < cutoff:
                        shutil.rmtree(p, ignore_errors=True)
                        removed += 1
                except OSError:
                    pass
        mbx = os.path.join(users, uid, "mbx")
        if os.path.isdir(mbx):
            for dev in os.listdir(mbx):
                for f in os.listdir(os.path.join(mbx, dev)):
                    p = os.path.join(mbx, dev, f)
                    try:
                        if os.path.getmtime(p) < cutoff:
                            os.remove(p)
                            removed += 1
                    except OSError:
                        pass
    return removed
