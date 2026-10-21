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
    "Point — политика конфиденциальности": (
        "Point — політика конфіденційності",
        "Point — privacy policy",
    ),
    "Политика конфиденциальности": ("Політика конфіденційності", "Privacy policy"),
    "Point работает с объектом на самом устройстве. На сервер попадает только то, что "
    "нужно, чтобы узнать вас, передать объект между вашими устройствами или отдать его "
    "другому человеку по вашей ссылке. Эти правила — для приложений Point на телефоне "
    "и компьютере и для их сервера point.leerio.app.": (
        "Point працює з об’єктом на самому пристрої. На сервер потрапляє лише те, що "
        "потрібно, щоб упізнати вас, передати об’єкт між вашими пристроями або віддати його "
        "іншій людині за вашим посиланням. Ці правила — для застосунків Point на телефоні "
        "й комп’ютері та для їхнього сервера point.leerio.app.",
        "Point works with your things on the device itself. The server only gets what it needs "
        "to recognise you, pass things between your devices, or hand something to another "
        "person through your link. These rules cover the Point apps on your phone and computer "
        "and their server, point.leerio.app.",
    ),
    "Между вашими устройствами передаётся только то, что вы отправили сами, и в "
    "зашифрованном виде: сервер не может это прочитать.": (
        "Між вашими пристроями передається лише те, що ви надіслали самі, і в "
        "зашифрованому вигляді: сервер не може це прочитати.",
        "Only what you send yourself travels between your devices, and it travels encrypted: "
        "the server cannot read it.",
    ),
    "То, что идёт по ссылке к другому человеку или от него, сервер видит, иначе ссылку не "
    "открыть в обычном браузере. Не позже чем через сутки это стирается само.": (
        "Те, що йде за посиланням до іншої людини або від неї, сервер бачить, інакше посилання "
        "не відкрити у звичайному браузері. Не пізніше ніж за добу це стирається саме.",
        "Whatever travels by link to or from another person is visible to the server, or the "
        "link couldn't open in an ordinary browser. It erases itself within a day.",
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
    "Название устройства, его вид (телефон или компьютер), открытая половина его ключа, "
    "адрес, по которому компьютер может разбудить телефон, и время последнего обращения — "
    "чтобы вы видели свой круг и могли отключить лишнее.": (
        "Назва пристрою, його вид (телефон чи комп’ютер), відкрита половина його ключа, "
        "адреса, за якою комп’ютер може розбудити телефон, і час останнього звернення — "
        "щоб ви бачили своє коло й могли відключити зайве.",
        "The device's name, its kind (phone or computer), the public half of its key, the "
        "address your computer uses to wake your phone, and when it last checked in, so you "
        "can see your circle and switch off what you don't need.",
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
    "Что вы отдали другому человеку по ссылке": (
        "Що ви віддали іншій людині за посиланням",
        "What you hand to another person by link",
    ),
    "Лежит на сервере сутки и стирается само. Сервер это видит, иначе получатель не открыл бы "
    "ссылку в обычном браузере. Поэтому Point спрашивает каждый раз, прежде чем выложить "
    "объект по ссылке, а удаление аккаунта сразу гасит все такие ссылки.": (
        "Лежить на сервері добу й стирається саме. Сервер це бачить, інакше одержувач не "
        "відкрив би посилання у звичайному браузері. Тому Point щоразу питає, перш ніж "
        "викласти об’єкт за посиланням, а видалення акаунта одразу гасить усі такі посилання.",
        "It stays on the server for a day and then erases itself. The server can see it, or the "
        "recipient couldn't open the link in an ordinary browser. That's why Point asks every "
        "time before putting something behind a link, and deleting your account switches all "
        "such links off at once.",
    ),
    "Ссылки на приём файла": ("Посилання для приймання файлу", "Links for receiving a file"),
    "Ссылка живёт сутки. Файл, который по ней прислали, лежит на сервере, пока ваше "
    "устройство его не заберёт, и не дольше суток. Сервер его видит: отправитель загружает "
    "файл обычным браузером.": (
        "Посилання живе добу. Файл, який за ним надіслали, лежить на сервері, доки ваш "
        "пристрій його не забере, і не довше доби. Сервер його бачить: відправник завантажує "
        "файл звичайним браузером.",
        "The link lasts a day. A file sent through it waits on the server until your device "
        "picks it up, and never longer than a day. The server can see it: the sender uploads "
        "it with an ordinary browser.",
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
    "Не продаёт ваши данные. Чужому сервису они уходят только с вашего согласия — такие "
    "сервисы названы ниже.": (
        "Не продає ваші дані. Сторонньому сервісу вони йдуть лише з вашої згоди — такі "
        "сервіси названо нижче.",
        "It doesn't sell your data. Your data goes to another service only with your consent; "
        "those services are named below.",
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
    "Исключение одно — режим YOLO. Если вы сами включили его в настройках, Point "
    "больше не спрашивает и отправляет объект чужому сервису сразу.": (
        "Виняток один — режим YOLO. Якщо ви самі ввімкнули його в налаштуваннях, Point "
        "більше не питає й надсилає об’єкт сторонньому сервісу одразу.",
        "There is one exception: YOLO mode. If you turn it on yourself in settings, Point "
        "stops asking and sends things to another service straight away.",
    ),
    "Вход": ("Вхід", "Sign-in"),
    "Google. Point получает от него только имя и почту.": (
        "Google. Point отримує від нього лише ім’я та пошту.",
        "Google. Point gets only your name and email from it.",
    ),
    "Пробуждение телефона": ("Пробудження телефона", "Waking your phone"),
    "Google Firebase. Когда компьютер просит телефон о чём-то, через Google идёт только "
    "сигнал «зайди», без содержимого.": (
        "Google Firebase. Коли комп’ютер просить телефон про щось, через Google іде лише "
        "сигнал «зайди», без вмісту.",
        "Google Firebase. When your computer asks your phone for something, only a “come and "
        "look” signal goes through Google, with nothing inside.",
    ),
    "Чтение текста со сложной фотографии": (
        "Читання тексту зі складної фотографії",
        "Reading text from a difficult photo",
    ),
    "OCR.space (Германия) или OVHcloud (Франция).": (
        "OCR.space (Німеччина) або OVHcloud (Франція).",
        "OCR.space (Germany) or OVHcloud (France).",
    ),
    "Действия с AI": ("Дії з AI", "AI steps"),
    "Сервис, ключ которого вы добавили сами: например Google Gemini, Anthropic Claude, OpenAI "
    "или Groq. Куда именно уйдёт объект, Point называет до отправки, а что сервис делает с "
    "полученным, решают его собственные правила.": (
        "Сервіс, ключ якого ви додали самі: наприклад, Google Gemini, Anthropic Claude, OpenAI "
        "або Groq. Куди саме піде об’єкт, Point називає до надсилання, а що сервіс робить з "
        "отриманим, вирішують його власні правила.",
        "The service whose key you added yourself, for example Google Gemini, Anthropic Claude, "
        "OpenAI or Groq. Point names where a thing goes before sending it, and what the service "
        "does with it is up to that service's own rules.",
    ),
    "Что Point берёт на телефоне": ("Що Point бере на телефоні", "What Point uses on your phone"),
    "Местоположение": ("Місцезнаходження", "Location"),
    "Только когда вы сами создаёте объект «Место». В фоне Point его не узнаёт.": (
        "Лише коли ви самі створюєте об’єкт «Місце». У фоні Point його не дізнається.",
        "Only when you create a “Place” yourself. Point never checks it in the background.",
    ),
    "Микрофон": ("Мікрофон", "Microphone"),
    "Только пока вы записываете голос в Point.": (
        "Лише поки ви записуєте голос у Point.",
        "Only while you record your voice in Point.",
    ),
    "Буфер обмена": ("Буфер обміну", "Clipboard"),
    "Только когда вы сами отдаёте его Point: плиткой в шторке или кнопкой вставки.": (
        "Лише коли ви самі віддаєте його Point: плиткою в шторці або кнопкою вставлення.",
        "Only when you hand it to Point yourself: with the quick settings tile or a paste button.",
    ),
    "Файлы и текст": ("Файли й текст", "Files and text"),
    "Только те, которыми вы поделились с Point или которые открыли в нём.": (
        "Лише ті, якими ви поділилися з Point або які відкрили в ньому.",
        "Only the ones you shared with Point or opened in it.",
    ),
    "Всё это остаётся на устройстве, пока вы сами не отправите объект дальше.": (
        "Усе це лишається на пристрої, доки ви самі не надішлете об’єкт далі.",
        "All of this stays on the device until you send the thing on yourself.",
    ),
    "Как удалить": ("Як видалити", "How to delete"),
    "В приложении: «Мои устройства» → «Удалить аккаунт». Учётная запись, круг устройств, "
    "ваши ссылки и всё, что лежит на сервере недоставленным, исчезают сразу и навсегда. "
    "Объекты на самих устройствах остаются вашими — сервер к ним отношения не имеет.": (
        "У застосунку: «Мої пристрої» → «Видалити акаунт». Обліковий запис, коло пристроїв, "
        "ваші посилання і все, що лежить на сервері недоставленим, зникають одразу й назавжди. "
        "Об’єкти на самих пристроях лишаються вашими — сервер до них не має стосунку.",
        "In the app: “My devices” → “Delete account”. The account, the circle of devices, your "
        "links and everything waiting undelivered on the server disappear at once and for good. "
        "Things on the devices themselves stay yours; the server has nothing to do with them.",
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
    "Всё, что лежало на сервере недоставленным между устройствами. Все ссылки, по которым "
    "вы отдали объект другим людям, и все ссылки на приём файлов.": (
        "Обліковий запис Point і зв’язок із вашим акаунтом Google. Усі ваші пристрої в колі. "
        "Усе, що лежало на сервері недоставленим між пристроями. Усі посилання, за якими ви "
        "віддали об’єкт іншим людям, і всі посилання для приймання файлів.",
        "Your Point account and its link to your Google account. All your devices in the circle. "
        "Everything left undelivered on the server between devices. All links through which you "
        "handed something to other people, and all links for receiving files.",
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
