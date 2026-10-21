"""Каталог переводов сервера (#1473): русская строка → (украинский, английский).

Ключ — ровно та строка, что стоит в коде внутри `t(...)`. Подстановки (`%s`, `%d`) и разметка
в переводе те же, что в оригинале, — это проверяет `tests/test_i18n.py`, как и то, что ни одна
строка кода не осталась без перевода и ни одна строка каталога не пережила свой код.
"""
from __future__ import annotations

TEXTS: dict[str, tuple[str, str]] = {
    # --- общее для всех страниц ---------------------------------------------------------
    "О ваших данных": ("Про ваші дані", "About your data"),
    "передача между устройствами": ("передавання між пристроями", "transfer between devices"),
    "Готово": ("Готово", "Done"),
    "Скачать": ("Завантажити", "Download"),
    "Скачать файлом": ("Завантажити файлом", "Download as a file"),
    "Скопировать": ("Скопіювати", "Copy"),
    "Скопировано": ("Скопійовано", "Copied"),
    "%d Б": ("%d Б", "%d B"),
    "%d КБ": ("%d КБ", "%d KB"),
    "%.1f МБ": ("%.1f МБ", "%.1f MB"),
    " Б": (" Б", " B"),
    " КБ": (" КБ", " KB"),
    " МБ": (" МБ", " MB"),
    "час": ("година", "hour"),
    "часа": ("години", "hours"),
    "часов": ("годин", "hours"),
    # --- вход ---------------------------------------------------------------------------
    "Вход в Point": ("Вхід у Point", "Sign in to Point"),
    "Проверьте, что этот же код показан на устройстве, которое просит вход. "
    "Не совпал — закройте страницу.": (
        "Перевірте, що цей самий код показано на пристрої, який просить вхід. "
        "Не збігся — закрийте сторінку.",
        "Check that the same code is shown on the device asking to sign in. "
        "If it doesn't match, close this page.",
    ),
    "Войти через Google": ("Увійти через Google", "Sign in with Google"),
    "Point получит от Google только имя и почту. Что вы пересылаете между своими "
    "устройствами, сервер прочитать не может.": (
        "Point отримає від Google лише ім’я та пошту. Те, що ви пересилаєте між своїми "
        "пристроями, сервер прочитати не може.",
        "Point gets only your name and email from Google. The server cannot read what you send "
        "between your devices.",
    ),
    "Устройство вошло": ("Пристрій увійшов", "The device is signed in"),
    "Можно закрыть эту страницу и вернуться в Point.": (
        "Можна закрити цю сторінку й повернутися в Point.",
        "You can close this page and go back to Point.",
    ),
    "Вошло устройство с кодом %s.": (
        "Увійшов пристрій з кодом %s.",
        "Signed in: the device with code %s.",
    ),
    "Вход подтверждён": ("Вхід підтверджено", "Sign-in confirmed"),
    "Возвращаемся в Point. Если ничего не произошло — нажмите кнопку.": (
        "Повертаємося в Point. Якщо нічого не сталося — натисніть кнопку.",
        "Going back to Point. If nothing happens, press the button.",
    ),
    "Вернуться в Point": ("Повернутися в Point", "Back to Point"),
    "Ссылка входа больше не работает": (
        "Посилання для входу більше не працює",
        "This sign-in link no longer works",
    ),
    "Ссылка больше не работает": ("Посилання більше не працює", "This link no longer works"),
    "Вход живёт пять минут и открывается один раз. Начните вход на устройстве заново.": (
        "Вхід живе п’ять хвилин і відкривається один раз. Почніть вхід на пристрої заново.",
        "A sign-in lasts five minutes and opens only once. Start signing in on the device again.",
    ),
    "Войти не получилось": ("Увійти не вдалося", "Couldn't sign in"),
    "Попробуйте начать вход на устройстве заново.": (
        "Спробуйте почати вхід на пристрої заново.",
        "Try starting the sign-in on the device again.",
    ),
    "Google не подтвердил вход.": ("Google не підтвердив вхід.", "Google did not confirm the sign-in."),
    "обмен кода не удался": ("обмін коду не вдався", "code exchange failed"),
    "Google не вернул id_token": ("Google не повернув id_token", "Google returned no id_token"),
    "нет PyJWT — проверить подпись Google нечем (pip install -r requirements.txt)": (
        "немає PyJWT — перевірити підпис Google нічим (pip install -r requirements.txt)",
        "PyJWT is missing, so Google's signature can't be checked (pip install -r requirements.txt)",
    ),
    "подпись Google не проверилась": ("підпис Google не пройшов перевірку", "Google's signature did not verify"),
    "в id_token нет sub": ("в id_token немає sub", "id_token has no sub"),
    "вход по Google не настроен на сервере": (
        "вхід через Google не налаштовано на сервері",
        "sign-in with Google is not set up on the server",
    ),
    # --- о данных и удаление ------------------------------------------------------------
    "Point — о ваших данных": ("Point — про ваші дані", "Point — about your data"),
    "Point работает с объектом на самом устройстве. На сервер попадает только то, что "
    "нужно, чтобы узнать вас и передать объект между вашими устройствами.": (
        "Point працює з об’єктом на самому пристрої. На сервер потрапляє лише те, що "
        "потрібно, щоб упізнати вас і передати об’єкт між вашими пристроями.",
        "Point works with your things on the device itself. The server only gets what it needs "
        "to recognise you and pass things between your devices.",
    ),
    "Содержимое ваших объектов сервер не читает и не хранит.": (
        "Вміст ваших об’єктів сервер не читає і не зберігає.",
        "The server neither reads nor keeps what's inside your files.",
    ),
    "Между вашими устройствами передаётся только то, что вы отправили сами.": (
        "Між вашими пристроями передається лише те, що ви надіслали самі.",
        "Only what you send yourself travels between your devices.",
    ),
    "Присланное по ссылке живёт сутки и стирается само.": (
        "Надіслане за посиланням живе добу й стирається саме.",
        "Whatever is sent by link lasts a day and then erases itself.",
    ),
    "От Google Point получает имя и почту — больше ничего.": (
        "Від Google Point отримує ім’я та пошту — більше нічого.",
        "From Google, Point gets your name and email, nothing else.",
    ),
    "Удаление аккаунта стирает всё сразу и навсегда, без корзины на тридцать дней.": (
        "Видалення акаунта стирає все одразу й назавжди, без кошика на тридцять днів.",
        "Deleting your account erases everything at once and for good, with no thirty-day bin.",
    ),
    "Что хранится": ("Що зберігається", "What is kept"),
    "Аккаунт": ("Акаунт", "Account"),
    "Почта и имя из вашего аккаунта Google — чтобы узнавать вас на разных устройствах. "
    "Пароль Google ни Point, ни его сервер не видят: с Google разговаривает сервер, а к вам "
    "возвращается только пропуск этого устройства.": (
        "Пошта та ім’я з вашого акаунта Google — щоб упізнавати вас на різних пристроях. "
        "Пароль Google не бачать ні Point, ні його сервер: з Google розмовляє сервер, а до вас "
        "повертається лише перепустка цього пристрою.",
        "The email and name from your Google account, to recognise you on different devices. "
        "Neither Point nor its server sees your Google password: the server talks to Google, and "
        "only this device's pass comes back to you.",
    ),
    "Ваши устройства": ("Ваші пристрої", "Your devices"),
    "Название устройства, его вид (телефон или компьютер), открытая половина его ключа и "
    "время последнего обращения — чтобы вы видели свой круг и могли отключить лишнее.": (
        "Назва пристрою, його вид (телефон чи комп’ютер), відкрита половина його ключа й "
        "час останнього звернення — щоб ви бачили своє коло й могли відключити зайве.",
        "The device's name, its kind (phone or computer), the public half of its key and when it "
        "last checked in, so you can see your circle and switch off what you don't need.",
    ),
    "То, что вы пересылаете между своими устройствами": (
        "Те, що ви пересилаєте між своїми пристроями",
        "What you send between your devices",
    ),
    "Лежит на сервере, пока второе устройство не заберёт, и стирается сразу после. "
    "Содержимое зашифровано ключами ваших устройств — сервер его прочитать не может.": (
        "Лежить на сервері, доки другий пристрій не забере, і стирається одразу після. "
        "Вміст зашифровано ключами ваших пристроїв — сервер не може його прочитати.",
        "It waits on the server until your other device picks it up and is erased right after. "
        "The contents are encrypted with your devices' keys, so the server cannot read them.",
    ),
    "Ссылки на приём файла": ("Посилання для приймання файлу", "Links for receiving a file"),
    "Живут сутки, потом удаляются вместе с файлом.": (
        "Живуть добу, потім видаляються разом із файлом.",
        "They last a day and are then deleted along with the file.",
    ),
    "Чего Point не делает": ("Чого Point не робить", "What Point doesn't do"),
    "Не показывает рекламу и не передаёт ничего рекламным сетям.": (
        "Не показує рекламу й нічого не передає рекламним мережам.",
        "It shows no ads and passes nothing to ad networks.",
    ),
    "Не собирает статистику вашего поведения и не следит за тем, что вы открываете.": (
        "Не збирає статистику вашої поведінки й не стежить за тим, що ви відкриваєте.",
        "It collects no statistics on how you behave and doesn't watch what you open.",
    ),
    "Не продаёт и не передаёт ваши данные третьим лицам.": (
        "Не продає й не передає ваші дані третім особам.",
        "It doesn't sell or hand your data to third parties.",
    ),
    "Не читает объекты, которые вы разбираете на самом устройстве, — они не покидают телефон.": (
        "Не читає об’єкти, які ви розбираєте на самому пристрої, — вони не залишають телефон.",
        "It doesn't read the things you work with on the device itself; they never leave the phone.",
    ),
    "Чужие сервисы": ("Сторонні сервіси", "Other services"),
    "Некоторые действия выполняются не на устройстве, а чужим сервисом — например чтение "
    "текста со сложной фотографии. Такое действие всегда начинается вашим нажатием, и до "
    "нажатия наружу не уходит ничего. Что именно уходит и куда, Point говорит на том же "
    "экране, где спрашивает.": (
        "Деякі дії виконує не пристрій, а сторонній сервіс — наприклад, читання тексту зі "
        "складної фотографії. Така дія завжди починається з вашого натискання, і до натискання "
        "назовні не йде нічого. Що саме йде і куди, Point каже на тому самому екрані, де питає.",
        "Some steps run on another service instead of your device, such as reading text from a "
        "difficult photo. Such a step always starts with your tap, and nothing leaves before it. "
        "Point says what goes out and where on the same screen where it asks.",
    ),
    "Как удалить": ("Як видалити", "How to delete"),
    "В приложении: «Мои устройства» → «Удалить аккаунт». Учётная запись, круг устройств и "
    "всё, что лежит на сервере недоставленным, исчезают сразу и навсегда. Объекты на самих "
    "устройствах остаются вашими — сервер к ним отношения не имеет.": (
        "У застосунку: «Мої пристрої» → «Видалити акаунт». Обліковий запис, коло пристроїв і "
        "все, що лежить на сервері недоставленим, зникають одразу й назавжди. Об’єкти на "
        "самих пристроях лишаються вашими — сервер до них не має стосунку.",
        "In the app: “My devices” → “Delete account”. The account, the "
        "circle of devices and everything waiting undelivered on the server disappear at once "
        "and for good. Things on the devices themselves stay yours; the server has nothing to "
        "do with them.",
    ),
    'Подробнее — на странице <a class="go" href="/delete-account">Удаление аккаунта</a>.': (
        'Докладніше — на сторінці <a class="go" href="/delete-account">Видалення акаунта</a>.',
        'More on the <a class="go" href="/delete-account">Account deletion</a> page.',
    ),
    "Вопросы — librevlad@gmail.com": ("Питання — librevlad@gmail.com", "Questions: librevlad@gmail.com"),
    "Point — удаление аккаунта": ("Point — видалення акаунта", "Point — account deletion"),
    "Удаление аккаунта": ("Видалення акаунта", "Account deletion"),
    "В приложении Point: <b>Мои устройства → Удалить аккаунт → Удалить навсегда</b>.": (
        "У застосунку Point: <b>Мої пристрої → Видалити акаунт → Видалити назавжди</b>.",
        "In the Point app: <b>My devices → Delete account → Delete for good</b>.",
    ),
    "Что исчезает сразу": ("Що зникає одразу", "What disappears at once"),
    "Учётная запись Point и связь с вашим аккаунтом Google. Все ваши устройства в круге. "
    "Всё, что лежало на сервере недоставленным между устройствами. Все ссылки на приём "
    "файлов.": (
        "Обліковий запис Point і зв’язок із вашим акаунтом Google. Усі ваші пристрої в колі. "
        "Усе, що лежало на сервері недоставленим між пристроями. Усі посилання для приймання "
        "файлів.",
        "Your Point account and its link to your Google account. All your devices in the circle. "
        "Everything left undelivered on the server between devices. All links for receiving "
        "files.",
    ),
    "Что остаётся": ("Що лишається", "What stays"),
    "Объекты на самих устройствах: фотографии, документы, тексты. Сервер их не хранил и "
    "удалить их за вас не может — они ваши и лежат у вас.": (
        "Об’єкти на самих пристроях: фотографії, документи, тексти. Сервер їх не зберігав і "
        "видалити їх за вас не може — вони ваші й лежать у вас.",
        "Things on the devices themselves: photos, documents, texts. The server never kept them "
        "and cannot delete them for you; they are yours and stay with you.",
    ),
    "Отменить удаление нельзя, восстановить — тоже. Ничего не хранится «на всякий случай» "
    "и не ждёт тридцать дней в корзине.": (
        "Скасувати видалення не можна, відновити — теж. Нічого не зберігається «про всяк "
        "випадок» і не чекає тридцять днів у кошику.",
        "Deletion can't be undone or reversed. Nothing is kept “just in case” or waits "
        "thirty days in a bin.",
    ),
    "Нет доступа к приложению?": ("Немає доступу до застосунку?", "Can't open the app?"),
    "Напишите на librevlad@gmail.com с той почты, которой входили. Аккаунт будет удалён "
    "тем же способом, вручную.": (
        "Напишіть на librevlad@gmail.com з тієї пошти, якою входили. Акаунт буде видалено "
        "так само, вручну.",
        "Write to librevlad@gmail.com from the email you signed in with. The account will be "
        "deleted the same way, by hand.",
    ),
    # --- приём файла по ссылке ----------------------------------------------------------
    "Отправить в Point": ("Надіслати в Point", "Send to Point"),
    "Придёт человеку, который дал вам эту ссылку.": (
        "Прийде людині, яка дала вам це посилання.",
        "It goes to the person who gave you this link.",
    ),
    "Файл": ("Файл", "File"),
    "Текст": ("Текст", "Text"),
    "Контакт": ("Контакт", "Contact"),
    "Место": ("Місце", "Place"),
    "Выберите файл": ("Виберіть файл", "Choose a file"),
    "или перетащите сюда": ("або перетягніть сюди", "or drop it here"),
    "Напишите или вставьте текст…": ("Напишіть або вставте текст…", "Type or paste text…"),
    "Имя": ("Ім’я", "Name"),
    "Телефон": ("Телефон", "Phone"),
    "Почта — если есть": ("Пошта — якщо є", "Email, if any"),
    "Point попросит у браузера, где вы сейчас. Точку увидит только тот, кто дал вам ссылку.": (
        "Point попросить у браузера, де ви зараз. Точку побачить лише той, хто дав вам посилання.",
        "Point will ask your browser where you are. Only the person who gave you the link will "
        "see the spot.",
    ),
    "Отправить файл": ("Надіслати файл", "Send file"),
    "Отправить текст": ("Надіслати текст", "Send text"),
    "Отправить контакт": ("Надіслати контакт", "Send contact"),
    "Отправить место": ("Надіслати місце", "Send place"),
    "Файл уходит сразу после нажатия — до 50 МБ. Пока идёт отправка, не закрывайте страницу.": (
        "Файл іде одразу після натискання — до 50 МБ. Поки триває надсилання, не закривайте сторінку.",
        "The file goes as soon as you press, up to 50 MB. Keep this page open while it sends.",
    ),
    "Текст придёт текстом, а не файлом.": ("Текст прийде текстом, а не файлом.", "The text arrives as text, not as a file."),
    "Придёт контактом — его можно сохранить в телефон одним нажатием.": (
        "Прийде контактом — його можна зберегти в телефон одним натисканням.",
        "It arrives as a contact that can be saved to the phone in one tap.",
    ),
    "Придёт точкой на карте.": ("Прийде точкою на мапі.", "It arrives as a spot on the map."),
    '<b id="pc">0%</b><span>отправляю</span>': (
        '<b id="pc">0%</b><span>надсилаю</span>',
        '<b id="pc">0%</b><span>sending</span>',
    ),
    '<b>Выберите файл</b><span id="name"></span>': (
        '<b>Виберіть файл</b><span id="name"></span>',
        '<b>Choose a file</b><span id="name"></span>',
    ),
    "<b>Готово</b><span>": ("<b>Готово</b><span>", "<b>Done</b><span>"),
    "файл ушёл": ("файл пішов", "file sent"),
    "текст ушёл": ("текст пішов", "text sent"),
    "контакт ушёл": ("контакт пішов", "contact sent"),
    "место ушло": ("місце пішло", "place sent"),
    "Можно закрывать страницу. Чтобы прислать ещё что-то, попросите новую ссылку.": (
        "Можна закривати сторінку. Щоб надіслати ще щось, попросіть нове посилання.",
        "You can close this page. To send something else, ask for a new link.",
    ),
    "Ссылка больше не работает — попросите новую.": (
        "Посилання більше не працює — попросіть нове.",
        "This link no longer works. Ask for a new one.",
    ),
    "Файл слишком большой для этого ящика.": ("Файл завеликий для цієї скриньки.", "The file is too big for this box."),
    "Сервер не принял отправку. Попробуйте ещё раз.": (
        "Сервер не прийняв надсилання. Спробуйте ще раз.",
        "The server didn't accept it. Try again.",
    ),
    "Связь прервалась. Проверьте интернет и попробуйте ещё раз.": (
        "Зв’язок обірвався. Перевірте інтернет і спробуйте ще раз.",
        "The connection dropped. Check your internet and try again.",
    ),
    "Отправка прервана.": ("Надсилання перервано.", "Sending was stopped."),
    "Напишите текст — пока отправлять нечего": ("Напишіть текст — поки що нічого надсилати", "Type some text first; there's nothing to send yet"),
    "Выберите файл — пока отправлять нечего": ("Виберіть файл — поки що нічого надсилати", "Choose a file first; there's nothing to send yet"),
    "Браузер не умеет определять место": ("Браузер не вміє визначати місце", "This browser can't tell where you are"),
    "Спрашиваю, где вы…": ("Питаю, де ви…", "Asking where you are…"),
    "Место.txt": ("Місце.txt", "Place.txt"),
    "Место не разрешено — точку отправить нечем": (
        "Місце не дозволено — точку надіслати нічим",
        "Location isn't allowed, so there's no spot to send",
    ),
    "Заполните хотя бы имя или телефон": ("Заповніть хоча б ім’я або телефон", "Fill in at least a name or a phone"),
    "Файл отправлен": ("Файл надіслано", "File sent"),
    "Он уже у того, кто дал вам ссылку. Можно закрывать страницу.": (
        "Він уже в того, хто дав вам посилання. Можна закривати сторінку.",
        "The person who gave you the link has it now. You can close this page.",
    ),
    "Пока нечего отправлять": ("Поки що нічого надсилати", "Nothing to send yet"),
    "Выберите файл или напишите текст — и нажмите «Отправить».": (
        "Виберіть файл або напишіть текст — і натисніть «Надіслати».",
        "Choose a file or type some text, then press “Send”.",
    ),
    "Ссылки на приём файла живут сутки. Попросите новую у того, кто её дал.": (
        "Посилання для приймання файлу живуть добу. Попросіть нове в того, хто його дав.",
        "Links for receiving a file last a day. Ask whoever gave it to you for a new one.",
    ),
    "Не поместилось": ("Не вмістилося", "It didn't fit"),
    # --- отдача по ссылке ---------------------------------------------------------------
    "Вам прислали текст": ("Вам надіслали текст", "Someone sent you text"),
    "Вам прислали контакт": ("Вам надіслали контакт", "Someone sent you a contact"),
    "Вам прислали место": ("Вам надіслали місце", "Someone sent you a place"),
    "Вам прислали снимок": ("Вам надіслали знімок", "Someone sent you a picture"),
    "Вам прислали запись": ("Вам надіслали запис", "Someone sent you a recording"),
    "Вам прислали файл": ("Вам надіслали файл", "Someone sent you a file"),
    "Снимок": ("Знімок", "Picture"),
    "Запись": ("Запис", "Recording"),
    "Почта": ("Пошта", "Email"),
    "Организация": ("Організація", "Organisation"),
    "Адрес": ("Адреса", "Address"),
    "Архив": ("Архів", "Archive"),
    "Документ": ("Документ", "Document"),
    "Видео": ("Відео", "Video"),
    "Добавить в контакты": ("Додати в контакти", "Add to contacts"),
    "Открыть в картах": ("Відкрити в картах", "Open in maps"),
    "Если вы не ждали эту ссылку — не скачивайте файл.": (
        "Якщо ви не чекали цього посилання — не завантажуйте файл.",
        "If you weren't expecting this link, don't download the file.",
    ),
    "<small>Ссылка живёт сутки, потом присланное стирается само.</small>": (
        "<small>Посилання живе добу, потім надіслане стирається саме.</small>",
        "<small>The link lasts a day, then whatever was sent erases itself.</small>",
    ),
    "<small>Ссылка вот-вот перестанет работать — заберите присланное сейчас.</small>": (
        "<small>Посилання ось-ось перестане працювати — заберіть надіслане зараз.</small>",
        "<small>The link is about to stop working. Take what was sent now.</small>",
    ),
    "<small>Ссылка работает ещё %d мин, потом присланное стирается само.</small>": (
        "<small>Посилання працює ще %d хв, потім надіслане стирається саме.</small>",
        "<small>The link works for %d more min, then whatever was sent erases itself.</small>",
    ),
    "<small>Ссылка работает ещё %d %s, потом присланное стирается само.</small>": (
        "<small>Посилання працює ще %d %s, потім надіслане стирається саме.</small>",
        "<small>The link works for %d more %s, then whatever was sent erases itself.</small>",
    ),
    "Файла больше нет": ("Файлу більше немає", "The file is gone"),
    "Присланное живёт сутки. Попросите новую ссылку у того, кто её дал.": (
        "Надіслане живе добу. Попросіть нове посилання в того, хто його дав.",
        "What's sent lasts a day. Ask whoever gave you the link for a new one.",
    ),
    # --- отказы в ответах API -----------------------------------------------------------
    "Устройство не в аккаунте. Войдите заново.": (
        "Пристрій не в акаунті. Увійдіть заново.",
        "This device isn't in the account. Sign in again.",
    ),
    "Запрос не читается — проверьте данные и попробуйте ещё раз.": (
        "Запит не читається — перевірте дані й спробуйте ще раз.",
        "The request can't be read. Check the data and try again.",
    ),
    "Запрос не подходит по форме — проверьте данные и попробуйте ещё раз.": (
        "Запит не підходить за формою — перевірте дані й спробуйте ще раз.",
        "The request isn't in the right shape. Check the data and try again.",
    ),
    "Устройство бывает PHONE или PC.": ("Пристрій буває PHONE або PC.", "A device is either PHONE or PC."),
    "Вход не найден или уже завершён. Начните заново.": (
        "Вхід не знайдено або вже завершено. Почніть заново.",
        "The sign-in wasn't found or has already finished. Start again.",
    ),
    "Вход уже забран.": ("Вхід уже забрано.", "This sign-in has already been picked up."),
    "Устройство отключено.": ("Пристрій відключено.", "The device is switched off."),
    "Настройки приехали пустыми.": ("Налаштування прийшли порожніми.", "The settings arrived empty."),
    "На сервере лежат более новые настройки.": (
        "На сервері лежать новіші налаштування.",
        "The server has newer settings.",
    ),
    "Такого устройства в вашем круге нет.": (
        "Такого пристрою у вашому колі немає.",
        "There's no such device in your circle.",
    ),
    "Адрес для стука устройство сообщает только про себя.": (
        "Адресу для стуку пристрій повідомляє лише про себе.",
        "A device can only report its own knock address.",
    ),
    "Файл больше 50 МБ — столько сервер Point не берёт": (
        "Файл більший за 50 МБ — стільки сервер Point не бере",
        "The file is over 50 MB, more than the Point server takes",
    ),
    "На сервере кончилось место — заберите то, что уже отправлено": (
        "На сервері скінчилося місце — заберіть те, що вже надіслано",
        "The server is out of space. Pick up what has already been sent",
    ),
    "Почта между вашими устройствами переполнена — заберите отправленное": (
        "Пошта між вашими пристроями переповнена — заберіть надіслане",
        "The mail between your devices is full. Pick up what was sent",
    ),
    "Больше 20 живых ссылок сразу не бывает — дождитесь, пока старые истекут": (
        "Більше 20 живих посилань одночасно не буває — дочекайтеся, поки старі спливуть",
        "There can't be more than 20 live links at once. Wait for old ones to expire",
    ),
    "Больше 5 открытых ящиков приёма сразу не бывает": (
        "Більше 5 відкритих скриньок для приймання одночасно не буває",
        "There can't be more than 5 open receiving boxes at once",
    ),
    "В этот ящик больше не помещается — заберите присланное": (
        "У цю скриньку більше не вміщується — заберіть надіслане",
        "Nothing more fits in this box. Pick up what was sent",
    ),
}
