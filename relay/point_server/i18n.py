"""Язык страниц и ответов сервера — язык человека, а не сервера (#1473).

Point говорит на трёх языках: английском, украинском и русском. Какой нужен, сервер узнаёт из
`Accept-Language` — его шлёт браузер, и его же шлёт приложение. Ни один из трёх не подошёл,
или заголовка нет вовсе, — английский: его поймёт больше людей, чем любой другой из трёх.

Тексты в коде пишутся по-русски, как и раньше, и оборачиваются в `t(...)`; русская строка — ключ
каталога `TEXTS` (`i18n_texts.py`), где рядом лежат украинский и английский. Так страницы читаются
как прежде, а тест `test_i18n.py` не даёт появиться строке без перевода.

Язык держится в `ContextVar` на время одного запроса: ставит его прослойка в `app.py`, читают
страницы. Протаскивать язык параметром через каждую функцию страниц значило бы переписать их все
ради одного слова, которое у всего запроса одно.
"""
from __future__ import annotations

import contextvars

from .i18n_texts import TEXTS

LANGS = ("en", "uk", "ru")
DEFAULT = "en"

#: Строки, которых не нашлось в каталоге, — для теста, чтобы перевод не терялся молча.
MISSING: set[str] = set()

_lang: contextvars.ContextVar[str] = contextvars.ContextVar("point_lang", default=DEFAULT)


def lang_from(accept_language: str | None) -> str:
    """Первый из трёх языков в порядке, который просит клиент; иначе английский.

    `uk-UA,uk;q=0.9,ru;q=0.8,en;q=0.5` → `uk`. Вес `q=0` значит «не надо» и пропускается.
    """
    asked: list[tuple[float, int, str]] = []
    for i, part in enumerate((accept_language or "").split(",")):
        bits = [b.strip() for b in part.split(";")]
        tag = bits[0].lower()
        if not tag:
            continue
        q = 1.0
        for b in bits[1:]:
            if b.startswith("q="):
                try:
                    q = float(b[2:])
                except ValueError:
                    q = 0.0
        if q <= 0:
            continue
        asked.append((-q, i, tag.split("-")[0]))
    for _, _, base in sorted(asked):
        if base in LANGS:
            return base
    return DEFAULT


def use(lang: str) -> contextvars.Token:
    return _lang.set(lang if lang in LANGS else DEFAULT)


def reset(token: contextvars.Token) -> None:
    _lang.reset(token)


def current() -> str:
    return _lang.get()


def t(ru: str) -> str:
    """Строка на языке запроса. Перевода нет — русский оригинал, а не пустота."""
    lang = _lang.get()
    if lang == "ru":
        return ru
    pair = TEXTS.get(ru)
    if pair is None:
        MISSING.add(ru)
        return ru
    return pair[0] if lang == "uk" else pair[1]


def t_reason(reason: str) -> str:
    """Причина отказа, у которой после двоеточия может стоять техническая подробность.

    «обмен кода не удался: HTTPError» — переводится сама фраза, подробность остаётся как есть.
    """
    head, sep, tail = reason.partition(": ")
    if sep and reason not in TEXTS:
        return t(head) + sep + tail
    return t(reason)


def plural(n: int, one: str, few: str, many: str) -> str:
    """Форма слова при числе: русские три формы — ключ, перевод — по правилам своего языка.

    Украинский устроен как русский (1 / 2–4 / 5+), английский — 1 и всё остальное.
    """
    lang = _lang.get()
    if lang == "en":
        forms = TEXTS.get(one, (one, one))[1], TEXTS.get(many, (many, many))[1]
        return forms[0] if n == 1 else forms[1]
    form = one if n % 10 == 1 and n % 100 != 11 else (
        few if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14) else many
    )
    return t(form)
