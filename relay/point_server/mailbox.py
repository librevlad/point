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

import json
import os
import shutil
import threading
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


class TooBig(Full):
    """Один файл больше предела — дело в размере, а не в месте: отказ 413, не 507."""


class Busy(Exception):
    """Временно: прежние отправки человека ещё идут. Это не «места нет» — через минуту пройдёт."""


class Gone(Exception):
    """Ящика уже нет: человек удалил всё своё, пока чужая отправка ещё шла."""


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


_LOCKS: dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()


def user_lock(user_id: str) -> threading.RLock:
    """Замок человека: запись присланного и «Удалить всё моё» идут по очереди, не вперемешку.

    Иначе удаление проскакивало между проверкой «аккаунт жив» и записью, и ссылка или ящик
    переживали удаление (проверка независимыми рецензентами 27.11.2026). Сервер — один
    процесс, поэтому замка в памяти достаточно.
    """
    with _LOCKS_GUARD:
        lock = _LOCKS.get(user_id)
        if lock is None:
            lock = _LOCKS[user_id] = threading.RLock()
        return lock


def user_root(root: str, user_id: str) -> str:
    """Всё, что принадлежит человеку, лежит под одним каталогом — и удаляется одним движением."""
    return _dir(root, "u", user_id)


def used_bytes(root: str, user_id: str) -> int:
    # Только читать: каталог удалённого человека не должен рождаться заново от подсчёта места.
    return _size(os.path.join(root, "u", user_id))


def too_big() -> "TooBig":
    return TooBig("Файл больше 50 МБ — столько сервер Point не берёт")


def _guard_total(root: str, user_id: str, incoming: int) -> None:
    if incoming > MAX_BLOB:
        raise too_big()
    if used_bytes(root, user_id) + incoming > MAX_TOTAL_BYTES:
        raise Full("На сервере кончилось место — заберите то, что уже отправлено")


# --- приём кусками (охота 27.11.2026) ----------------------------------------------------
# Сервер — машина на 964 МБ памяти, общая с другими службами. Файл человека (до 50 МБ)
# раньше держался в памяти целиком, а то и в нескольких копиях. Теперь он пишется на диск по
# мере прихода в общий каталог приёма и оттуда одним переименованием встаёт на своё место.
#
# Недописанное лежит вне каталога человека, но не вне его счёта: имя файла начинается с того,
# кому он идёт, и [guard_incoming] считает его вместе с уже лежащим. Иначе параллельные или
# замершие отправки занимали бы диск мимо всех пределов (проверка независимыми рецензентами).
STAGING = "in"
STAGING_TTL_SECONDS = 3600     # недописанное после срыва убирает уборка
MAX_IN_FLIGHT = 3              # одновременных отправок на одного человека (считает app)


def _owner_tag(owner: str) -> str:
    return "".join(c for c in owner if c.isalnum() or c in "-_")[:64] or "anon"


def staging_path(root: str, owner: str = "") -> str:
    return os.path.join(_dir(root, STAGING), _owner_tag(owner) + "." + uuid.uuid4().hex + ".part")


def guard_incoming(root: str, owner: str, declared: int | None) -> None:
    """Пустить ли отправку человеку — до того, как прочитан её первый байт: размер и место.

    Сколько отправок идёт одновременно, считает приложение в памяти процесса: счёт по файлам
    на диске путался с сиротами прежнего процесса, а уборка сирот при старте рвала отправки,
    которые старый процесс ещё дописывал во время выкладки (проверка рецензентами).
    """
    if declared is not None and declared > MAX_BLOB:
        raise too_big()
    if used_bytes(root, owner) + (declared or 0) > MAX_TOTAL_BYTES:
        raise Full("На сервере кончилось место — заберите то, что уже отправлено")


def stage_bytes(root: str, data: bytes, owner: str = "") -> str:
    if len(data) > MAX_BLOB:
        raise too_big()
    path = staging_path(root, owner)
    with open(path, "wb") as f:
        f.write(data)
    return path


def discard(path: str) -> None:
    try:
        os.remove(path)
    except FileNotFoundError:
        pass


# --- почта между своими устройствами ----------------------------------------------------


def mailbox_dir(root: str, user_id: str, device_id: str) -> str:
    return _dir(user_root(root, user_id), "mbx", device_id)


def push(root: str, user_id: str, device_id: str, data: bytes) -> str:
    """Положить письмо в ящик своего же устройства. Содержимое запечатано устройствами."""
    return push_file(root, user_id, device_id, stage_bytes(root, data, user_id))


def push_file(root: str, user_id: str, device_id: str, staged: str) -> str:
    """То же, но письмо уже лежит на диске в [STAGING]: сюда оно приходит кусками, мимо памяти."""
    try:
        size = os.path.getsize(staged)
        _guard_total(root, user_id, size)
        box = mailbox_dir(root, user_id, device_id)
        if _size(_dir(user_root(root, user_id), "mbx")) + size > MAX_MAILBOX_BYTES:
            raise Full("Почта между вашими устройствами переполнена — заберите отправленное")
        bid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
        os.replace(staged, os.path.join(box, bid + ".bin"))
        return bid
    finally:
        discard(staged)


def pull_path(root: str, user_id: str, device_id: str) -> tuple[str, str] | None:
    """Самое старое письмо — путём к нему: отдаётся с диска кусками, не целиком в памяти.

    Только читает: долгое ожидание письма, закончившееся после «Удалить всё моё», не должно
    рождать каталог удалённого человека заново.
    """
    box = os.path.join(root, "u", user_id, "mbx", device_id)
    if not os.path.isdir(box):
        return None
    names = sorted(n for n in os.listdir(box) if n.endswith(".bin"))
    if not names:
        return None
    return names[0][:-4], os.path.join(box, names[0])


def pull(root: str, user_id: str, device_id: str) -> tuple[str, bytes] | None:
    """Забрать самое старое письмо, не удаляя: удалит `ack` — иначе потеря на разрыве связи."""
    got = pull_path(root, user_id, device_id)
    if not got:
        return None
    with open(got[1], "rb") as f:
        return got[0], f.read()


def ack(root: str, user_id: str, device_id: str, blob_id: str) -> bool:
    safe = "".join(c for c in blob_id if c.isalnum() or c in "-_")[:64]
    path = os.path.join(mailbox_dir(root, user_id, device_id), safe + ".bin")
    if os.path.isfile(path):
        os.remove(path)
        return True
    return False


# --- описание присланного: имя и тип -----------------------------------------------------


def write_meta(path: str, name: str, mime: str) -> None:
    """Записать имя и тип присланного.

    Разделять поля переводом строки нельзя: значение вправе его содержать, и тогда хвост
    имени становится типом. Чужой человек, кладущий файл в ящик приёма, выбирал этим тип
    того, что попадёт в Point, а хозяин видел безобидное имя (#927).

    Поэтому поля пишутся JSON-ом: там значение закавычено, и сдвинуть соседнее поле оно не
    может — чем бы его ни наполнили.
    """
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"name": _plain(name) or "file", "mime": _plain(mime) or DEFAULT_MIME}, f)


def read_meta(path: str) -> tuple[str, str]:
    """Прочитать имя и тип. Пусто или нечитаемо — безобидные значения по умолчанию.

    Старые записи лежат двумя строками: на боевом сервере они есть прямо сейчас, и человек
    не должен потерять присланное из-за смены формата.
    """
    try:
        with open(path, encoding="utf-8") as f:
            raw = f.read()
    except OSError:
        return "file", DEFAULT_MIME

    try:
        got = json.loads(raw)
        return _plain(got.get("name")) or "file", _plain(got.get("mime")) or DEFAULT_MIME
    except (ValueError, AttributeError):
        lines = raw.splitlines()
        name = _plain(lines[0] if lines else "") or "file"
        mime = _plain(lines[1] if len(lines) > 1 else "") or DEFAULT_MIME
        return name, mime


def _plain(value: str | None) -> str:
    """Строка без управляющих знаков: человеку они не значат ничего, формату — многое."""
    text = value or ""
    return "".join(" " if ch.isspace() or not ch.isprintable() else ch for ch in text).strip()


DEFAULT_MIME = "application/octet-stream"


# --- «Дать ссылку»: файл забирает чужой человек ------------------------------------------


def drops_dir(root: str, user_id: str) -> str:
    return _dir(user_root(root, user_id), "d")


def drop_put(root: str, user_id: str, data: bytes, name: str, mime: str) -> str:
    return drop_put_file(root, user_id, stage_bytes(root, data, user_id), name, mime)


def drop_put_file(root: str, user_id: str, staged: str, name: str, mime: str) -> str:
    """Файл ссылки уже на диске в [STAGING]: сюда он приходит кусками, мимо памяти."""
    try:
        _guard_total(root, user_id, os.path.getsize(staged))
        box_root = drops_dir(root, user_id)
        if len(os.listdir(box_root)) >= MAX_DROPS:
            raise Full("Больше 20 живых ссылок сразу не бывает — дождитесь, пока старые истекут")
        did = uuid.uuid4().hex + uuid.uuid4().hex[:8]  # 160 бит: ссылку не перебрать
        box = _dir(box_root, did)
        write_meta(os.path.join(box, "meta"), name, mime)
        os.replace(staged, os.path.join(box, "blob.bin"))
        return did
    finally:
        discard(staged)


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
            name, mime = read_meta(os.path.join(box, "meta"))
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


def inbox_close(root: str, user_id: str, box_id: str) -> bool:
    """
    Ящик закрывается, когда он больше не нужен (#729).

    Прежде его убирал только суточный sweep: пять открытий экрана «Принять файл» за сутки —
    и приём переставал работать до утра. Предел в пять ссылок защищает от злоупотребления,
    а не от обычного использования.
    """
    safe = "".join(c for c in box_id if c.isalnum())[:80]
    if not safe:
        return False
    box = os.path.join(inboxes_dir(root, user_id), safe)
    if not os.path.isdir(box):
        return False
    shutil.rmtree(box, ignore_errors=True)
    return not os.path.isdir(box)


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
    # Текст — через каталог приёма, как и файл: иначе он считался в место владельца дважды.
    root, owner = box_owner(box)
    inbox_accept_file(box, stage_bytes(root, data, owner), name, mime)


def box_owner(box: str) -> tuple[str, str]:
    """(корень, человек) ящика приёма: ящик лежит в `<корень>/u/<человек>/i/<ящик>`."""
    user_dir = os.path.dirname(os.path.dirname(box))
    return os.path.dirname(os.path.dirname(user_dir)), os.path.basename(user_dir)


def inbox_accept_file(box: str, staged: str, name: str, mime: str) -> None:
    """Присланное чужим уже на диске: страница приёма пишет его туда кусками, мимо памяти.

    Присланное ложится в место владельца ящика — значит, и считается его местом. Прежде
    страница приёма шла мимо его предела: чужие файлы занимали сотни мегабайт, и потом уже
    собственные письма человека получали «на сервере кончилось место» (проверка рецензентами).
    """
    root, owner = box_owner(box)
    try:
        with user_lock(owner):
            _accept_into(box, root, owner, staged, name, mime)
    finally:
        discard(staged)


def _accept_into(box: str, root: str, owner: str, staged: str, name: str, mime: str) -> None:
    """Под замком владельца: ящик ещё жив, место есть — тогда файл встаёт на место."""
    if not os.path.isdir(box):
        raise Gone()
    _guard_total(root, owner, os.path.getsize(staged))
    if len([n for n in os.listdir(box) if n.endswith(".bin")]) >= MAX_INBOX_FILES:
        raise Full("В этот ящик больше не помещается — заберите присланное")
    fid = "%020d-%s" % (time.time_ns(), uuid.uuid4().hex[:8])
    write_meta(os.path.join(box, fid + ".meta"), name, mime)
    os.replace(staged, os.path.join(box, fid + ".bin"))


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


def inbox_take_path(root: str, user_id: str, box_id: str) -> tuple[str, str, str, str] | None:
    """Присланное — путём к файлу: отдаётся с диска кусками, не целиком в памяти."""
    safe = "".join(c for c in box_id if c.isalnum())[:80]
    box = os.path.join(root, "u", user_id, "i", safe)
    if not os.path.isdir(box):
        return None
    names = sorted(n for n in os.listdir(box) if n.endswith(".bin"))
    if not names:
        return None
    fid = names[0][:-4]
    name, mime = read_meta(os.path.join(box, fid + ".meta"))
    return fid, os.path.join(box, fid + ".bin"), name, mime


def inbox_take(root: str, user_id: str, box_id: str) -> tuple[str, bytes, str, str] | None:
    """Забрать присланное — уже под пропуском: ящик свой, значит и владелец подставляется."""
    got = inbox_take_path(root, user_id, box_id)
    if not got:
        return None
    fid, path, name, mime = got
    with open(path, "rb") as f:
        return fid, f.read(), name, mime


# --- уборка ------------------------------------------------------------------------------


def forget_user(root: str, user_id: str) -> None:
    """«Удалить всё моё» — немедленно, не дожидаясь суток; под замком, не посреди записи."""
    with user_lock(user_id):
        shutil.rmtree(os.path.join(root, "u", user_id), ignore_errors=True)


def forget_device(root: str, user_id: str, device_id: str) -> None:
    """«Выйти» на устройстве стирает его ящик: чужой почте там больше не место."""
    shutil.rmtree(mailbox_dir(root, user_id, device_id), ignore_errors=True)


def time_left(path: str, now: float | None = None) -> float:
    """Сколько присланному осталось жить (#1388).

    Считается тем же правилом, которым его сотрёт [sweep]: срок, названный человеку, и срок, по
    которому файл исчезнет, обязаны быть одним числом. Файла уже нет — остатка нет тоже.
    """
    now = time.time() if now is None else now
    try:
        return TTL_SECONDS - (now - os.path.getmtime(path))
    except OSError:
        return 0.0


def sweep(root: str, now: float | None = None) -> int:
    """Всё старше суток исчезает само. Возвращает, сколько каталогов убрано."""
    now = time.time() if now is None else now
    cutoff = now - TTL_SECONDS
    removed = 0
    # Недописанное после оборванного приёма — не файл человека: ему хватит часа.
    staging = os.path.join(root, STAGING)
    if os.path.isdir(staging):
        for f in os.listdir(staging):
            p = os.path.join(staging, f)
            try:
                if os.path.getmtime(p) < now - STAGING_TTL_SECONDS:
                    os.remove(p)
                    removed += 1
            except OSError:
                pass
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
