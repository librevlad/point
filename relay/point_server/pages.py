"""Страницы входа. Ни одного чужого домена: ни шрифтов, ни скриптов, ни картинок.

Правило то же, что у страницы приёма файла в релее: человек и так открывает незнакомую
ссылку, и подтягивать в неё что-то извне было бы наглостью. Скриптов нет вовсе — обычная
форма работает и на старом телефоне, и с выключенным JS.
"""
from __future__ import annotations

import html

PAGE_STYLE = """
*{box-sizing:border-box}
body{margin:0;padding:24px;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0E1014;color:#F2F3F5;
font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif}
main{width:100%;max-width:420px;background:linear-gradient(#1A1D25,#121419);border:1px solid #FFFFFF14;
border-radius:18px;padding:24px}
h1{margin:0 0 8px;font-size:22px;font-weight:600}
p{margin:0 0 16px;font-size:15px;line-height:1.5;color:#A8ADB8}
.code{display:block;margin:0 0 16px;padding:14px;border:1px dashed #FFFFFF2E;border-radius:12px;
background:#00000033;color:#F2F3F5;font-size:28px;font-weight:600;letter-spacing:4px;text-align:center}
button{width:100%;padding:14px;border:0;border-radius:12px;background:#7C5CFF;color:#fff;
font-size:16px;font-weight:600;cursor:pointer}
button:active{background:#6A4BE8}
small{display:block;margin-top:16px;font-size:13px;line-height:1.5;color:#7E8492}
"""


def page(title: str, body: str) -> str:
    return (
        '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>%s</title><style>%s</style></head><body><main>%s</main></body></html>"
        % (html.escape(title), PAGE_STYLE, body)
    )


def login_page(login_id: str, code: str) -> str:
    """Код на странице и код на устройстве обязаны совпасть — иначе человек подтвердит чужой вход."""
    return page(
        "Вход в Point",
        "<h1>Вход в Point</h1>"
        "<p>Проверьте, что этот же код показан на устройстве, которое просит вход. "
        "Не совпал — закройте страницу.</p>"
        '<span class="code">%s</span>'
        '<form method="post" action="/login">'
        '<input type="hidden" name="d" value="%s">'
        '<button type="submit">Войти через Google</button></form>'
        "<small>Point получит от Google только имя и почту. Что вы пересылаете между своими "
        "устройствами, сервер прочитать не может.</small>"
        % (html.escape(code), html.escape(login_id)),
    )


def done_page(code: str) -> str:
    return page(
        "Готово",
        "<h1>Готово</h1><p>Устройство с кодом ниже входит в аккаунт. Можно закрывать страницу.</p>"
        '<span class="code">%s</span>' % html.escape(code),
    )


def sent_page() -> str:
    """Страницу приёма видит чужой человек — ему не рассказывают ни про вход, ни про коды."""
    return page(
        "Файл отправлен",
        "<h1>Файл отправлен</h1>"
        "<p>Он уже у того, кто дал вам ссылку. Можно закрывать страницу.</p>",
    )


def link_gone_page() -> str:
    return page(
        "Ссылка больше не работает",
        "<h1>Ссылка больше не работает</h1>"
        "<p>Ссылки на приём файла живут сутки. Попросите новую у того, кто её дал.</p>",
    )


def too_big_page(reason: str) -> str:
    return page(
        "Не поместилось",
        "<h1>Не поместилось</h1><p>%s</p>" % html.escape(reason),
    )


def gone_page() -> str:
    return page(
        "Ссылка входа больше не работает",
        "<h1>Ссылка больше не работает</h1>"
        "<p>Вход живёт пять минут и открывается один раз. Начните вход на устройстве заново.</p>",
    )


def failed_page(reason: str) -> str:
    return page(
        "Войти не получилось",
        "<h1>Войти не получилось</h1><p>%s</p>"
        "<small>Попробуйте начать вход на устройстве заново.</small>" % html.escape(reason),
    )
