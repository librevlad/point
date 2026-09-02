"""
Страницы drop — единственная поверхность Point, которую видит чужой человек (#883).

Без установленного приложения, часто с телефона, часто первый раз в жизни. Для него эти
страницы и есть Point целиком: он должен понять, что ему прислали, что сейчас произойдёт и
до когда живёт ссылка.
"""
import base64


def _drop(point, name, mime, data):
    # Имя едет base64: в заголовке HTTP кириллице места нет.
    me = point.sign_in(sub="dropper-" + str(abs(hash(name)) % 10000))
    put = point.as_device(
        me["device_token"], "POST", "/d",
        content=data,
        headers={
            "X-Drop-Name": base64.b64encode(name.encode("utf-8")).decode("ascii"),
            "X-Drop-Mime": mime,
        },
    )
    assert put.status_code == 200, put.text
    return put.headers["X-Drop-Id"]


def test_снимок_виден_на_странице_а_не_качается_вслепую(point):
    """Раньше браузер молча качал файл, о котором человек ничего не знал."""
    did = _drop(point, "чек.jpg", "image/jpeg", b"\xff\xd8\xff\xe0" + b"x" * 2048)

    got = point.client.get("/d/" + did)

    assert got.status_code == 200
    assert "text/html" in got.headers["content-type"]
    assert "<img" in got.text, "снимок не показан"
    assert "?raw=1" in got.text, "скачивание пропало"
    assert "живёт сутки" in got.text, "срок жизни ссылки не назван"


def test_запись_слышно_прямо_на_странице(point):
    """Голосовое — самое частое, чем делятся ссылкой, и самое бесполезное вложением."""
    did = _drop(point, "голосовое.m4a", "audio/mp4", b"\x00\x00\x00\x20ftypM4A " + b"x" * 512)

    got = point.client.get("/d/" + did)

    assert got.status_code == 200
    assert "<audio" in got.text, "запись нельзя послушать"
    assert "controls" in got.text


def test_файл_называет_себя_до_скачивания(point):
    """PDF показать нельзя — но человек должен знать, что ему прислали и сколько это весит."""
    did = _drop(point, "Договор-2026.pdf", "application/pdf", b"%PDF-1.7" + b"x" * (300 * 1024))

    got = point.client.get("/d/" + did)

    assert got.status_code == 200
    assert "Договор-2026.pdf" in got.text
    assert "PDF" in got.text, "вид присланного не назван"
    assert "КБ" in got.text or "МБ" in got.text, "вес не назван"
    assert "не ждали эту ссылку" in got.text, "нет предупреждения о неожиданной ссылке"


def test_сам_файл_по_прежнему_отдаётся_по_raw(point):
    """Страница не отменяет скачивание — она делает его вторым шагом."""
    body = b"%PDF-1.7" + b"x" * 1024
    did = _drop(point, "счёт.pdf", "application/pdf", body)

    got = point.client.get("/d/" + did + "?raw=1")

    assert got.status_code == 200
    assert got.content == body
    assert "attachment" in got.headers["content-disposition"]


def test_текст_по_прежнему_читается_страницей(point):
    """То, что уже работало, должно продолжать работать."""
    did = _drop(point, "заказ.txt", "text/plain", "Заказ 4417, забрать до 19:00".encode("utf-8"))

    got = point.client.get("/d/" + did)

    assert "Заказ 4417" in got.text
    assert "Скопировать" in got.text


def test_текст_можно_отправить_через_ссылку_приёма(point):
    """Ссылку чаще всего дают ради куска текста — адреса, номера заказа, обрывка переписки."""
    me = point.sign_in(sub="textsender")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    sent = point.client.post("/u/" + box, data={"text": "Крещатик 22, забрать до 19:00"})

    assert sent.status_code == 200, sent.text
    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert got.status_code == 200
    assert "Крещатик 22".encode("utf-8") in got.content


def test_на_странице_приёма_есть_вкладка_текста(point):
    me = point.sign_in(sub="texttab")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert "Текст" in html, "отправить текст нечем"
    assert "textarea" in html


def test_пустая_отправка_говорит_про_себя_а_не_про_размер(point):
    """Раньше на «ничего не выбрано» отвечали страницей про размер файла."""
    me = point.sign_in(sub="emptysend")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    sent = point.client.post("/u/" + box, data={"text": "   "})

    assert sent.status_code == 400
    assert "нечего отправлять" in sent.text
    assert "МБ" not in sent.text, "чужая причина про размер"


def test_ссылка_видна_на_тёмном_фоне(point):
    """
    Обычная ссылка на странице красилась браузером в свой тёмно-синий (#0000EE), а фон
    страниц — почти чёрный: «Открыть в картах» и «Скачать файлом» человек едва различал
    (ревью страниц 02.09.2026). У ссылки должен быть свой цвет, не браузерный.
    """
    did = _drop(point, "склад.txt", "text/plain", "50.4501, 30.5234".encode("utf-8"))

    got = point.client.get("/d/" + did)

    assert "main a:not(.go){color:" in got.text, "ссылке не задан свой цвет — её не видно"


def test_каждая_страница_говорит_что_человеку_прислали(point):
    """
    Три страницы из шести показывали присланное значение заголовком: чужой человек
    открывал ссылку и видел «АНДРІЯЩЕНКО Артур» или «Заметка.txt» — без единого слова
    о том, что произошло (ревью страниц 02.09.2026, Grok). Снимок, запись и файл всегда
    говорили «Вам прислали …» — теперь так говорят все.
    """
    cases = [
        ("контакт.vcf", "text/vcard", b"BEGIN:VCARD\nFN:Artur\nTEL:+380665262706\nEND:VCARD"),
        ("заметка.txt", "text/plain", "Накладная № 1187".encode("utf-8")),
    ]
    for name, mime, data in cases:
        did = _drop(point, name, mime, data)

        got = point.client.get("/d/" + did)

        assert "Вам прислали" in got.text, f"страница «{name}» не говорит, что прислали"
