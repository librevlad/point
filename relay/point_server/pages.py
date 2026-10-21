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
import json

from .i18n import current, plural, t

PAGE_STYLE = """
/* Стекло Point, то же, что у телефона и окна ПК (макет «Point · концепт в стиле Gestalt»,
   19.10.2026; палитра — core/ui/src/shared/.../PointPalette.kt, обои — GlassSurfaces.kt).
   Страницы сервера оставались на прежней почти чёрной земле с ярким фиолетовым: человек
   шёл из приложения в стекле на страницу другого продукта. Шрифт Ubuntu — только если он
   уже стоит у человека: тянуть шрифт с чужого домена страница не станет. */
*{box-sizing:border-box}
body{margin:0;padding:24px 16px;min-height:100vh;display:flex;align-items:center;justify-content:center;
background-color:#2B2531;background-image:
radial-gradient(circle at 18% 8%,#805678C7,#80567800 46%),
radial-gradient(circle at 96% 26%,#D6805C33,#D6805C00 40%),
radial-gradient(circle at 46% 92%,#545E8CB8,#545E8C00 52%),
radial-gradient(circle at 92% 96%,#80567885,#80567800 32%);
background-attachment:fixed;color:#EDE7EA;
font-family:Ubuntu,-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif}
main{width:100%;max-width:420px;padding:24px;border-radius:24px;
background:#241F2AA3;border:1px solid #FFFFFF1F;border-top-color:#FFFFFF38;
box-shadow:0 24px 60px #00000040;-webkit-backdrop-filter:blur(24px);backdrop-filter:blur(24px)}
h1{margin:0 0 8px;font-size:22px;font-weight:500}
p{margin:0 0 16px;font-size:15px;line-height:1.5;color:#EDE7EABD}
.code{display:block;margin:0 0 16px;padding:14px;border:1px dashed #FFFFFF2E;border-radius:14px;
background:#FFFFFF0E;color:#EDE7EA;font-family:'Ubuntu Mono',ui-monospace,Menlo,Consolas,monospace;
font-size:28px;font-weight:600;letter-spacing:4px;text-align:center}
/* Главный шаг — светлая строка, тёмный текст, как «Новый объект» и первое действие. */
button,a.go{display:block;width:100%;padding:14px;border:1px solid #FFFFFFF2;border-radius:14px;
background:linear-gradient(#F1ECF4F7,#E5DEEDEB);box-shadow:0 8px 20px #00000033;
color:#2B2531;font:inherit;font-size:16px;font-weight:500;cursor:pointer;text-align:center;text-decoration:none}
button:active,a.go:active{background:linear-gradient(#E5DEEDF2,#D9D1E3E6)}
/* Обычная ссылка на тёмном стекле: браузер красит её своим тёмно-синим (#0000EE), и человек
   её почти не видит — «Открыть в картах» и «Скачать файлом» пропадали (ревью страниц
   02.09.2026). Цвет — светлый фиолетовый Point для слова-ссылки. */
main a:not(.go){color:#CBBEEE;text-decoration:underline;text-underline-offset:2px}
main a:not(.go):hover{color:#E2D9F7}
small{display:block;margin-top:16px;font-size:13px;line-height:1.5;color:#EDE7EA94}
small.warn{border-left:2px solid #D98B63;padding-left:10px;margin-top:12px;color:#EFBE9F}
.shot{display:block;width:100%;border-radius:14px;border:1px solid #FFFFFF1F;margin:0 0 12px}
.player{display:block;width:100%;margin:0 0 12px}
.what{color:#EDE7EA;font-weight:500;margin-bottom:4px}
/* Чужой человек открывает незнакомую ссылку и должен видеть, куда попал (#1388).
   Две строки по построению: знак, имя и дверь к обещанию в одной, пояснение под ними. На
   узком телефоне пояснение иначе переносилось и наезжало на ссылку. */
.brand{display:flex;flex-wrap:wrap;align-items:center;column-gap:8px;margin:0 0 20px;
padding-bottom:12px;border-bottom:1px solid #FFFFFF14;font-size:13px;color:#EDE7EA94}
.brand svg{flex:none;width:20px;height:20px}
.brand b{font-size:16px;font-weight:500;color:#EDE7EA;letter-spacing:.2px}
.brand a{margin-left:auto;white-space:nowrap}
.brand span{flex:0 0 100%;margin-top:2px;padding-left:28px}
/* Главное на странице о данных читается списком, а не пятью абзацами. */
.brief{margin:0 0 16px;padding:12px 14px;border-radius:14px;background:#FFFFFF0E;border:1px solid #FFFFFF14}
.brief p{margin:0 0 6px;font-size:14px;color:#EDE7EAD9}
.brief p:last-child{margin-bottom:0}
/* Кнопка не липнет к предупреждению, которое человек обязан прочесть до неё. */
small + a.go,small + button{margin-top:16px}
"""


#: Срок жизни ссылки человек читает одинаково на всех страницах отдачи (#883).
LIVES_A_DAY = "<small>Ссылка живёт сутки, потом присланное стирается само.</small>"


def link_lifetime(left_seconds: float | None = None) -> str:
    """Сколько у человека осталось времени, а не сколько ссылка живёт вообще (#1388).

    «Живёт сутки» не отвечает на единственный вопрос, от которого зависит, заберёт человек
    присланное сейчас или потеряет: сутки от чего? Ссылку могли переслать через двадцать часов
    после того, как её создали.

    Названо остатком, а не часом на календаре («до завтра, 14:00»): получатель бывает в другом
    часовом поясе, чем сервер, и абсолютное время увело бы его ровно там, где он и так не уверен.
    Остаток одинаково верен откуда угодно.

    Остаток неизвестен — говорится прежнее общее правило: врать про часы нельзя.
    """
    if left_seconds is None:
        return t(LIVES_A_DAY)
    left = max(0, int(left_seconds))
    if left < 60:
        return t("<small>Ссылка вот-вот перестанет работать — заберите присланное сейчас.</small>")
    if left < 3600:
        return t("<small>Ссылка работает ещё %d мин, потом присланное стирается само.</small>") % (
            left // 60
        )
    hours = left // 3600
    return t("<small>Ссылка работает ещё %d %s, потом присланное стирается само.</small>") % (
        hours,
        _hours_word(hours),
    )


def _hours_word(hours: int) -> str:
    return plural(hours, "час", "часа", "часов")


#: Знак Point — тот же портал, что у иконки приложения и в шапке окна ПК (`PointMark.kt`):
#: кольцо с переходом белый → фиолетовый → голубой. Доли те же: внешний край 0.583, толщина
#: 0.257 половины кадра. Рисунок встроен — ни одного запроса за картинкой.
MARK = (
    '<svg viewBox="0 0 24 24" aria-hidden="true"><defs><linearGradient id="pm" '
    'gradientUnits="userSpaceOnUse" x1="0" y1="5" x2="0" y2="19">'
    '<stop offset="0" stop-color="#EAF0FF"/><stop offset=".45" stop-color="#9B7BFF"/>'
    '<stop offset="1" stop-color="#00A6FF"/></linearGradient></defs>'
    '<circle cx="12" cy="12" r="5.46" fill="none" stroke="url(#pm)" stroke-width="3.08"/></svg>'
)

#: Страница называет себя (#1388).
#:
#: Чужой человек открывает незнакомую ссылку с телефона, первый раз в жизни, и видит карточку
#: без единого признака того, что это за сервис и можно ли ему верить. Имя и одна строка о том,
#: чем сервис занят, стоят на каждой странице; рядом — дверь к обещанию о данных, чтобы
#: недоверчивому было куда пойти проверить, а не только гадать.
def brand() -> str:
    return (
        '<p class="brand">' + MARK + '<b>Point</b><a href="/privacy">' + t("О ваших данных") + "</a>"
        "<span>" + t("передача между устройствами") + "</span></p>"
    )


def page(title: str, body: str, head: str = "") -> str:
    return (
        '<!doctype html><html lang="%s" class="no-js"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "%s<title>%s</title><style>%s</style></head><body><main>%s%s</main></body></html>"
        % (current(), head, html.escape(title), PAGE_STYLE, brand(), body)
    )


def login_page(login_id: str, code: str) -> str:
    """Код на странице и код на устройстве обязаны совпасть — иначе человек подтвердит чужой вход."""
    return page(
        t("Вход в Point"),
        "<h1>%s</h1><p>%s</p>"
        '<span class="code">%s</span>'
        '<form method="post" action="/login">'
        '<input type="hidden" name="d" value="%s">'
        '<button type="submit">%s</button></form>'
        "<small>%s</small>"
        % (
            t("Вход в Point"),
            t("Проверьте, что этот же код показан на устройстве, которое просит вход. "
              "Не совпал — закройте страницу."),
            html.escape(code),
            html.escape(login_id),
            t("Войти через Google"),
            t("Point получит от Google только имя и почту. Что вы пересылаете между своими "
              "устройствами, сервер прочитать не может."),
        ),
    )


def done_page(code: str) -> str:
    """Код здесь больше нечего сверять — сверка была на прошлом экране (#1388).

    «Вход» и «Готово» показывали одинаковый крупный моноширинный блок, и человек не понимал,
    закончилось что-то или его снова о чём-то просят. Код остаётся в подписи — узнать, какое
    устройство вошло, по-прежнему можно, — но экран говорит прямо, что дело сделано.
    """
    return page(
        t("Готово"),
        "<h1>" + t("Устройство вошло") + "</h1>"
        "<p>" + t("Можно закрыть эту страницу и вернуться в Point.") + "</p>"
        "<small>" + t("Вошло устройство с кодом %s.") % html.escape(code) + "</small>",
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
        t("Вход подтверждён"),
        "<h1>" + t("Вход подтверждён") + "</h1>"
        "<p>" + t("Возвращаемся в Point. Если ничего не произошло — нажмите кнопку.") + "</p>"
        '<a class="go" href="%s">' % safe + t("Вернуться в Point") + "</a>",
        head='<meta http-equiv="refresh" content="0;url=%s">' % safe,
    )


#: Главное — первым, до подробностей (#1388).
#:
#: Страницу читают в двух разных состояниях: недоверчивый человек хочет за десять секунд понять,
#: видит ли сервер его файлы, а магазин требует полного текста. Раньше ответ на первый вопрос
#: тонул в ответе на второй. Короткий список стоит сверху и ничего не заменяет: подробности
#: остаются под ним слово в слово, иначе страница перестала бы годиться магазину.
SHORT_ANSWER = (
        "Содержимое ваших объектов сервер не читает и не хранит.",
        "Между вашими устройствами передаётся только то, что вы отправили сами.",
        "Присланное по ссылке живёт сутки и стирается само.",
        "От Google Point получает имя и почту — больше ничего.",
        "Удаление аккаунта стирает всё сразу и навсегда, без корзины на тридцать дней.",
)


def short_answer() -> str:
    return '<div class="brief">' + "".join("<p>· %s</p>" % html.escape(t(v)) for v in SHORT_ANSWER) + "</div>"


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
        "<p><b>%s.</b> %s</p>" % (html.escape(t(k)), html.escape(t(v))) for k, v in WHAT_WE_KEEP
    )
    dont = "".join("<p>%s</p>" % html.escape(t(v)) for v in WHAT_WE_DO_NOT)
    return page(
        t("Point — о ваших данных"),
        "<h1>" + t("О ваших данных") + "</h1>"
        "<p>" + t("Point работает с объектом на самом устройстве. На сервер попадает только то, что "
                  "нужно, чтобы узнать вас и передать объект между вашими устройствами.") + "</p>"
        + short_answer() +
        "<h1>" + t("Что хранится") + "</h1>" + keep +
        "<h1>" + t("Чего Point не делает") + "</h1>" + dont +
        "<h1>" + t("Чужие сервисы") + "</h1>"
        "<p>" + t("Некоторые действия выполняются не на устройстве, а чужим сервисом — например чтение "
                  "текста со сложной фотографии. Такое действие всегда начинается вашим нажатием, и до "
                  "нажатия наружу не уходит ничего. Что именно уходит и куда, Point говорит на том же "
                  "экране, где спрашивает.") + "</p>"
        "<h1>" + t("Как удалить") + "</h1>"
        "<p>" + t("В приложении: «Мои устройства» → «Удалить аккаунт». Учётная запись, круг устройств и "
                  "всё, что лежит на сервере недоставленным, исчезают сразу и навсегда. Объекты на самих "
                  "устройствах остаются вашими — сервер к ним отношения не имеет.") + "</p>"
        "<p>" + t('Подробнее — на странице <a class="go" href="/delete-account">Удаление аккаунта</a>.') + "</p>"
        "<small>" + t("Вопросы — librevlad@gmail.com") + "</small>",
    )


def deletion_page() -> str:
    """Как стереть себя. Магазин требует эту страницу отдельно от политики и без установки."""
    return page(
        t("Point — удаление аккаунта"),
        "<h1>" + t("Удаление аккаунта") + "</h1>"
        "<p>" + t("В приложении Point: <b>Мои устройства → Удалить аккаунт → Удалить навсегда</b>.") + "</p>"
        "<h1>" + t("Что исчезает сразу") + "</h1>"
        "<p>" + t("Учётная запись Point и связь с вашим аккаунтом Google. Все ваши устройства в круге. "
                  "Всё, что лежало на сервере недоставленным между устройствами. Все ссылки на приём "
                  "файлов.") + "</p>"
        "<h1>" + t("Что остаётся") + "</h1>"
        "<p>" + t("Объекты на самих устройствах: фотографии, документы, тексты. Сервер их не хранил и "
                  "удалить их за вас не может — они ваши и лежат у вас.") + "</p>"
        "<p>" + t("Отменить удаление нельзя, восстановить — тоже. Ничего не хранится «на всякий случай» "
                  "и не ждёт тридцать дней в корзине.") + "</p>"
        "<h1>" + t("Нет доступа к приложению?") + "</h1>"
        "<p>" + t("Напишите на librevlad@gmail.com с той почты, которой входили. Аккаунт будет удалён "
                  "тем же способом, вручную.") + "</p>",
    )


def sent_page() -> str:
    """Страницу приёма видит чужой человек — ему не рассказывают ни про вход, ни про коды."""
    return page(
        t("Файл отправлен"),
        "<h1>" + t("Файл отправлен") + "</h1>"
        "<p>" + t("Он уже у того, кто дал вам ссылку. Можно закрывать страницу.") + "</p>",
    )


def nothing_to_send_page() -> str:
    """
    Отправлять нечего — это состояние человека, а не ошибка формы (#883).

    Раньше здесь показывалась страница «не поместилось» с чужой причиной: человек не
    выбирал файл, а ему отвечали про размер.
    """
    return page(
        t("Пока нечего отправлять"),
        "<h1>" + t("Пока нечего отправлять") + "</h1>"
        "<p>" + t("Выберите файл или напишите текст — и нажмите «Отправить».") + "</p>",
    )


def link_gone_page() -> str:
    return page(
        t("Ссылка больше не работает"),
        "<h1>" + t("Ссылка больше не работает") + "</h1>"
        "<p>" + t("Ссылки на приём файла живут сутки. Попросите новую у того, кто её дал.") + "</p>",
    )


def drop_text_page(name: str, text: str, download_url: str, left: float | None = None) -> str:
    """
    Присланный текст читается прямо со страницы (#780-класс).

    Текст уходил вложением: человек получал ссылку, браузер скачивал файл, и прочитать
    присланное можно было только открыв его чем-то ещё. Point держал текст в руках и отдавал
    в худшей форме. Файл никуда не делся — он рядом, для тех, кому нужен именно файл.
    """
    title = name or t("Текст")
    return page(
        title,
        "<h1>%s</h1>"
        '<p class="what">%s</p>'
        '<textarea id="t" readonly rows="16">%s</textarea>'
        '<p><button id="c" type="button">%s</button> '
        '<a href="%s" download>%s</a></p>'
        % (
            t("Вам прислали текст"),
            html.escape(title),
            html.escape(text),
            t("Скопировать"),
            html.escape(download_url),
            t("Скачать файлом"),
        )
        + link_lifetime(left),
        head="<script>"
        "document.addEventListener('DOMContentLoaded',function(){"
        "var b=document.getElementById('c'),t=document.getElementById('t'),done=%s;"
        "b.addEventListener('click',function(){"
        "t.select();navigator.clipboard.writeText(t.value).then(function(){b.textContent=done},"
        "function(){document.execCommand('copy');b.textContent=done})})})"
        "</script>" % json.dumps(t("Скопировано")),
    )


def drop_contact_page(name: str, fields: list[tuple[str, str]], download_url: str, left: float | None = None) -> str:
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
    title = name or t("Контакт")
    return page(
        title,
        "<h1>%s</h1>"
        '<p class="what">%s</p>%s'
        '<p><a href="%s" download>%s</a></p>'
        % (t("Вам прислали контакт"), html.escape(title), rows, html.escape(download_url), t("Добавить в контакты"))
        + link_lifetime(left),
    )


def drop_place_page(name: str, coordinates: str, download_url: str, left: float | None = None) -> str:
    """
    Присланное место открывается картой, а не скачивается (#737).

    Куда именно идти — вопрос человека и его телефона, поэтому здесь ссылка `geo:`, которую
    подхватит установленная карта, а не чужой сервис, выбранный за него.
    """
    title = name or t("Место")
    return page(
        title,
        "<h1>%s</h1>"
        '<p class="what">%s</p>'
        '<p><a href="geo:%s">%s</a></p>'
        "<p>%s</p>"
        '<p><a href="%s" download>%s</a></p>'
        % (
            t("Вам прислали место"),
            html.escape(title),
            html.escape(coordinates),
            t("Открыть в картах"),
            html.escape(coordinates),
            html.escape(download_url),
            t("Скачать файлом"),
        )
        + link_lifetime(left),
    )


def drop_image_page(name: str, download_url: str, size: str, left: float | None = None) -> str:
    """
    Присланный снимок виден сразу, а не скачивается вслепую (#883).

    Чужой человек открывал ссылку — и браузер молча начинал качать файл, о котором тот
    ничего не знал. Показать снимок можно прямо здесь; скачивание остаётся вторым шагом
    для тех, кому нужен именно файл.
    """
    title = name or t("Снимок")
    return page(
        title,
        "<h1>%s</h1>"
        '<img class="shot" src="%s" alt="%s">'
        "<p class=\"what\">%s%s</p>"
        '<a class="go" href="%s" download>%s</a>'
        "%s"
        % (
            t("Вам прислали снимок"),
            html.escape(download_url),
            html.escape(title),
            html.escape(title),
            (" · " + html.escape(size)) if size else "",
            html.escape(download_url),
            t("Скачать"),
            link_lifetime(left),
        ),
    )


def drop_audio_page(name: str, download_url: str, mime: str, size: str, left: float | None = None) -> str:
    """
    Присланную запись можно послушать прямо здесь (#883).

    Голосовое — самое частое, чем делятся ссылкой, и самое бесполезное вложением: чтобы
    услышать десять секунд, человек скачивал файл и искал, чем его открыть.
    """
    title = name or t("Запись")
    return page(
        title,
        "<h1>%s</h1>"
        '<audio class="player" controls preload="metadata" src="%s"></audio>'
        "<p class=\"what\">%s%s</p>"
        '<a class="go" href="%s" download>%s</a>'
        "%s"
        % (
            t("Вам прислали запись"),
            html.escape(download_url),
            html.escape(title),
            (" · " + html.escape(size)) if size else "",
            html.escape(download_url),
            t("Скачать"),
            link_lifetime(left),
        ),
    )


def drop_file_page(name: str, download_url: str, what: str, size: str, left: float | None = None) -> str:
    """
    Файл, который нельзя показать, хотя бы называет себя (#883).

    PDF, архив, документ по-прежнему скачиваются — но человек сначала видит, что именно
    ему прислали, сколько это весит и сколько осталось времени, и решает сам.

    Кнопка стоит последней (#1388). Ссылку могли переслать дальше, чем рассчитывал хозяин, и
    большая кнопка первой тянула палец раньше, чем человек успевал прочитать предупреждение:
    сначала что прислали и до когда, потом «не ждали — не скачивайте», и только потом «Скачать».
    """
    title = name or t("Файл")
    return page(
        title,
        "<h1>%s</h1>"
        "<p class=\"what\">%s</p>"
        "<p>%s%s</p>"
        "%s"
        '<small class="warn">%s</small>'
        '<a class="go" href="%s" download>%s</a>'
        % (
            t("Вам прислали файл"),
            html.escape(title),
            html.escape(what),
            (" · " + html.escape(size)) if size else "",
            link_lifetime(left),
            t("Если вы не ждали эту ссылку — не скачивайте файл."),
            html.escape(download_url),
            t("Скачать"),
        ),
    )


def drop_gone_page() -> str:
    return page(
        t("Файла больше нет"),
        "<h1>" + t("Файла больше нет") + "</h1>"
        "<p>" + t("Присланное живёт сутки. Попросите новую ссылку у того, кто её дал.") + "</p>",
    )


def too_big_page(reason: str) -> str:
    return page(
        t("Не поместилось"),
        "<h1>" + t("Не поместилось") + "</h1><p>%s</p>" % html.escape(reason),
    )


def gone_page() -> str:
    return page(
        t("Ссылка входа больше не работает"),
        "<h1>" + t("Ссылка больше не работает") + "</h1>"
        "<p>" + t("Вход живёт пять минут и открывается один раз. Начните вход на устройстве заново.") + "</p>",
    )


def failed_page(reason: str) -> str:
    return page(
        t("Войти не получилось"),
        "<h1>" + t("Войти не получилось") + "</h1><p>%s</p>" % html.escape(reason)
        + "<small>" + t("Попробуйте начать вход на устройстве заново.") + "</small>",
    )
