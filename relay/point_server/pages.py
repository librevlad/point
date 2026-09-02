"""Страницы входа. Ни одного чужого домена: ни шрифтов, ни скриптов, ни картинок.

Человек и так открывает незнакомую ссылку, и подтягивать в неё что-то извне было бы
наглостью. На самих страницах входа скриптов нет — обычная форма работает и на старом
телефоне, и с выключенным JS.

Страница приёма файла (`upload_page`) — исключение, разрешённое владельцем 2026-08-10:
без скрипта отправку нечем показать, и многомегабайтный снимок читался как зависание.
Скрипт там свой и встроенный, чужих доменов по-прежнему нет, а без JS страница остаётся
обычной формой и работает.
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
button,a.go{display:block;width:100%;padding:14px;border:0;border-radius:12px;background:#7C5CFF;
color:#fff;font-size:16px;font-weight:600;cursor:pointer;text-align:center;text-decoration:none}
button:active,a.go:active{background:#6A4BE8}
/* Обычная ссылка на тёмном фоне: браузер красит её своим тёмно-синим (#0000EE), и на
   #0E1014 человек её почти не видит — «Открыть в картах» и «Скачать файлом» пропадали
   (ревью страниц 02.09.2026). Цвет — тот же сиреневый, что у кнопок. */
main a:not(.go){color:#A78BFA;text-decoration:underline;text-underline-offset:2px}
main a:not(.go):hover{color:#C4B5FD}
small{display:block;margin-top:16px;font-size:13px;line-height:1.5;color:#7E8492}
small.warn{border-left:2px solid #FF6B6B80;padding-left:10px;margin-top:12px}
.shot{display:block;width:100%;border-radius:12px;border:1px solid #FFFFFF14;margin:0 0 12px}
.player{display:block;width:100%;margin:0 0 12px}
.what{color:#F2F3F5;font-weight:600;margin-bottom:4px}
"""


#: Срок жизни ссылки человек читает одинаково на всех страницах отдачи (#883).
LIVES_A_DAY = "<small>Ссылка живёт сутки, потом присланное стирается само.</small>"


def page(title: str, body: str, head: str = "") -> str:
    return (
        '<!doctype html><html lang="ru" class="no-js"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "%s<title>%s</title><style>%s</style></head><body><main>%s</main></body></html>"
        % (head, html.escape(title), PAGE_STYLE, body)
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


def return_page(app_url: str) -> str:
    """Вход кончился там же, где начался, — страница уводит человека обратно сама (#561).

    Переход сделан `meta refresh`, а не скриптом: правило страниц («ни одного чужого домена, ни
    одного скрипта») держится, и возврат работает с выключенным JS. Браузер может не пустить
    приложение без нажатия — поэтому под ним стоит та же дверь кнопкой. Тупика нет ни в одном
    случае: даже если человек не нажмёт ничего, вход уже состоялся, и Point дожмёт его сам, когда
    человек вернётся (см. `SignInDriver.resume`).
    """
    safe = html.escape(app_url, quote=True)
    return page(
        "Вход подтверждён",
        "<h1>Вход подтверждён</h1>"
        "<p>Возвращаемся в Point. Если ничего не произошло — нажмите кнопку.</p>"
        '<a class="go" href="%s">Вернуться в Point</a>' % safe,
        head='<meta http-equiv="refresh" content="0;url=%s">' % safe,
    )


#: Что Point знает о человеке — один список на две страницы и на анкету магазина.
#:
#: Держится здесь, а не в трёх местах, потому что расхождение между обещанием на странице и
#: правдой в анкете — это ровно тот случай, когда магазин снимает приложение.
WHAT_WE_KEEP = [
    (
        "Аккаунт",
        "Почта и имя из вашего аккаунта Google — чтобы узнавать вас на разных устройствах. "
        "Пароль Google ни Point, ни его сервер не видят: с Google разговаривает сервер, а к вам "
        "возвращается только пропуск этого устройства.",
    ),
    (
        "Ваши устройства",
        "Название устройства, его вид (телефон или компьютер), открытая половина его ключа и "
        "время последнего обращения — чтобы вы видели свой круг и могли отключить лишнее.",
    ),
    (
        "То, что вы пересылаете между своими устройствами",
        "Лежит на сервере, пока второе устройство не заберёт, и стирается сразу после. "
        "Содержимое зашифровано ключами ваших устройств — сервер его прочитать не может.",
    ),
    (
        "Ссылки на приём файла",
        "Живут сутки, потом удаляются вместе с файлом.",
    ),
]

#: Чего Point не делает. Отрицания названы поимённо: «мы заботимся о приватности» не значит ничего.
WHAT_WE_DO_NOT = [
    "Не показывает рекламу и не передаёт ничего рекламным сетям.",
    "Не собирает статистику вашего поведения и не следит за тем, что вы открываете.",
    "Не продаёт и не передаёт ваши данные третьим лицам.",
    "Не читает объекты, которые вы разбираете на самом устройстве, — они не покидают телефон.",
]


def privacy_page() -> str:
    """Обещание о данных — по-русски и без юридического тумана.

    Магазин требует такую страницу отдельным адресом, доступным без установки приложения. Здесь
    она отвечает на три вопроса человека: что о нём знают, что с этим делают и как это стереть.
    """
    keep = "".join(
        "<p><b>%s.</b> %s</p>" % (html.escape(t), html.escape(v)) for t, v in WHAT_WE_KEEP
    )
    dont = "".join("<p>%s</p>" % html.escape(v) for v in WHAT_WE_DO_NOT)
    return page(
        "Point — о ваших данных",
        "<h1>О ваших данных</h1>"
        "<p>Point работает с объектом на самом устройстве. На сервер попадает только то, что "
        "нужно, чтобы узнать вас и передать объект между вашими устройствами.</p>"
        "<h1>Что хранится</h1>" + keep +
        "<h1>Чего Point не делает</h1>" + dont +
        "<h1>Чужие сервисы</h1>"
        "<p>Некоторые действия выполняются не на устройстве, а чужим сервисом — например чтение "
        "текста со сложной фотографии. Такое действие всегда начинается вашим нажатием, и до "
        "нажатия наружу не уходит ничего. Что именно уходит и куда, Point говорит на том же "
        "экране, где спрашивает.</p>"
        "<h1>Как удалить</h1>"
        "<p>В приложении: «Мои устройства» → «Удалить аккаунт». Учётная запись, круг устройств и "
        "всё, что лежит на сервере недоставленным, исчезают сразу и навсегда. Объекты на самих "
        "устройствах остаются вашими — сервер к ним отношения не имеет.</p>"
        '<p>Подробнее — на странице <a class="go" href="/delete-account">Удаление аккаунта</a>.</p>'
        "<small>Вопросы — librevlad@gmail.com</small>",
    )


def deletion_page() -> str:
    """Как стереть себя. Магазин требует эту страницу отдельно от политики и без установки."""
    return page(
        "Point — удаление аккаунта",
        "<h1>Удаление аккаунта</h1>"
        "<p>В приложении Point: <b>Мои устройства → Удалить аккаунт → Удалить навсегда</b>.</p>"
        "<h1>Что исчезает сразу</h1>"
        "<p>Учётная запись Point и связь с вашим аккаунтом Google. Все ваши устройства в круге. "
        "Всё, что лежало на сервере недоставленным между устройствами. Все ссылки на приём "
        "файлов.</p>"
        "<h1>Что остаётся</h1>"
        "<p>Объекты на самих устройствах: фотографии, документы, тексты. Сервер их не хранил и "
        "удалить их за вас не может — они ваши и лежат у вас.</p>"
        "<p>Отменить удаление нельзя, восстановить — тоже. Ничего не хранится «на всякий случай» "
        "и не ждёт тридцать дней в корзине.</p>"
        "<h1>Нет доступа к приложению?</h1>"
        "<p>Напишите на librevlad@gmail.com с той почты, которой входили. Аккаунт будет удалён "
        "тем же способом, вручную.</p>",
    )


def sent_page() -> str:
    """Страницу приёма видит чужой человек — ему не рассказывают ни про вход, ни про коды."""
    return page(
        "Файл отправлен",
        "<h1>Файл отправлен</h1>"
        "<p>Он уже у того, кто дал вам ссылку. Можно закрывать страницу.</p>",
    )


def nothing_to_send_page() -> str:
    """
    Отправлять нечего — это состояние человека, а не ошибка формы (#883).

    Раньше здесь показывалась страница «не поместилось» с чужой причиной: человек не
    выбирал файл, а ему отвечали про размер.
    """
    return page(
        "Пока нечего отправлять",
        "<h1>Пока нечего отправлять</h1>"
        "<p>Выберите файл или напишите текст — и нажмите «Отправить».</p>",
    )


def link_gone_page() -> str:
    return page(
        "Ссылка больше не работает",
        "<h1>Ссылка больше не работает</h1>"
        "<p>Ссылки на приём файла живут сутки. Попросите новую у того, кто её дал.</p>",
    )


def drop_text_page(name: str, text: str, download_url: str) -> str:
    """
    Присланный текст читается прямо со страницы (#780-класс).

    Текст уходил вложением: человек получал ссылку, браузер скачивал файл, и прочитать
    присланное можно было только открыв его чем-то ещё. Point держал текст в руках и отдавал
    в худшей форме. Файл никуда не делся — он рядом, для тех, кому нужен именно файл.
    """
    return page(
        name or "Текст",
        "<h1>Вам прислали текст</h1>"
        '<p class="what">%s</p>'
        '<textarea id="t" readonly rows="16">%s</textarea>'
        '<p><button id="c" type="button">Скопировать</button> '
        '<a href="%s" download>Скачать файлом</a></p>'
        % (html.escape(name or "Текст"), html.escape(text), html.escape(download_url))
        + LIVES_A_DAY,
        head="<script>"
        "document.addEventListener('DOMContentLoaded',function(){"
        "var b=document.getElementById('c'),t=document.getElementById('t');"
        "b.addEventListener('click',function(){"
        "t.select();navigator.clipboard.writeText(t.value).then(function(){b.textContent='Скопировано'},"
        "function(){document.execCommand('copy');b.textContent='Скопировано'})})})"
        "</script>",
    )


def drop_contact_page(name: str, fields: list[tuple[str, str]], download_url: str) -> str:
    """
    Присланный контакт показан контактом, а не файлом (#737).

    Человеку по ссылке приходило вложение `.vcf`: чтобы узнать хотя бы имя, его надо было
    скачать и чем-то открыть. Имя, телефон и почта видны сразу; файл рядом — для того, кто
    хочет положить контакт в телефонную книжку.
    """
    rows = "".join(
        "<p><b>%s</b><br>%s</p>" % (html.escape(label), html.escape(value))
        for label, value in fields
    )
    return page(
        name or "Контакт",
        "<h1>Вам прислали контакт</h1>"
        '<p class="what">%s</p>%s'
        '<p><a href="%s" download>Добавить в контакты</a></p>'
        % (html.escape(name or "Контакт"), rows, html.escape(download_url))
        + LIVES_A_DAY,
    )


def drop_place_page(name: str, coordinates: str, download_url: str) -> str:
    """
    Присланное место открывается картой, а не скачивается (#737).

    Куда именно идти — вопрос человека и его телефона, поэтому здесь ссылка `geo:`, которую
    подхватит установленная карта, а не чужой сервис, выбранный за него.
    """
    return page(
        name or "Место",
        "<h1>Вам прислали место</h1>"
        '<p class="what">%s</p>'
        '<p><a href="geo:%s">Открыть в картах</a></p>'
        "<p>%s</p>"
        '<p><a href="%s" download>Скачать файлом</a></p>'
        % (
            html.escape(name or "Место"),
            html.escape(coordinates),
            html.escape(coordinates),
            html.escape(download_url),
        )
        + LIVES_A_DAY,
    )


def drop_image_page(name: str, download_url: str, size: str) -> str:
    """
    Присланный снимок виден сразу, а не скачивается вслепую (#883).

    Чужой человек открывал ссылку — и браузер молча начинал качать файл, о котором тот
    ничего не знал. Показать снимок можно прямо здесь; скачивание остаётся вторым шагом
    для тех, кому нужен именно файл.
    """
    return page(
        name or "Снимок",
        "<h1>Вам прислали снимок</h1>"
        '<img class="shot" src="%s" alt="%s">'
        "<p class=\"what\">%s%s</p>"
        '<a class="go" href="%s" download>Скачать</a>'
        "%s"
        % (
            html.escape(download_url),
            html.escape(name or "Снимок"),
            html.escape(name or "Снимок"),
            (" · " + html.escape(size)) if size else "",
            html.escape(download_url),
            LIVES_A_DAY,
        ),
    )


def drop_audio_page(name: str, download_url: str, mime: str, size: str) -> str:
    """
    Присланную запись можно послушать прямо здесь (#883).

    Голосовое — самое частое, чем делятся ссылкой, и самое бесполезное вложением: чтобы
    услышать десять секунд, человек скачивал файл и искал, чем его открыть.
    """
    return page(
        name or "Запись",
        "<h1>Вам прислали запись</h1>"
        '<audio class="player" controls preload="metadata" src="%s"></audio>'
        "<p class=\"what\">%s%s</p>"
        '<a class="go" href="%s" download>Скачать</a>'
        "%s"
        % (
            html.escape(download_url),
            html.escape(name or "Запись"),
            (" · " + html.escape(size)) if size else "",
            html.escape(download_url),
            LIVES_A_DAY,
        ),
    )


def drop_file_page(name: str, download_url: str, what: str, size: str) -> str:
    """
    Файл, который нельзя показать, хотя бы называет себя (#883).

    PDF, архив, документ по-прежнему скачиваются — но человек сначала видит, что именно
    ему прислали и сколько это весит, и решает сам. Ссылку могли переслать дальше, чем
    рассчитывал хозяин, поэтому здесь же стоит тихое предупреждение.
    """
    return page(
        name or "Файл",
        "<h1>Вам прислали файл</h1>"
        "<p class=\"what\">%s</p>"
        "<p>%s%s</p>"
        '<a class="go" href="%s" download>Скачать</a>'
        "%s"
        '<small class="warn">Если вы не ждали эту ссылку — не скачивайте файл.</small>'
        % (
            html.escape(name or "Файл"),
            html.escape(what),
            (" · " + html.escape(size)) if size else "",
            html.escape(download_url),
            LIVES_A_DAY,
        ),
    )


def drop_gone_page() -> str:
    return page(
        "Файла больше нет",
        "<h1>Файла больше нет</h1>"
        "<p>Присланное живёт сутки. Попросите новую ссылку у того, кто её дал.</p>",
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
