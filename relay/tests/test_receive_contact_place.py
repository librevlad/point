"""Приём места и контакта через drop (#916).

Отдача их уже понимала: контакт показывался страницей с именем и полями, место — картой.
Прислать их было нечем: форма приёма знала только «Файл» и «Текст». Контакт можно было
приложить разве что файлом, а место — никак.
"""


def test_контакт_приходит_контактом_а_не_текстом(point):
    me = point.sign_in(sub="vcard-in")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Світлана\r\nTEL;TYPE=CELL:+380671234567\r\nEND:VCARD\r\n"

    sent = point.client.post("/u/" + box, data={"text": card, "name": "Світлана.vcf"})

    assert sent.status_code == 200, sent.text
    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert got.status_code == 200
    assert "vcard" in got.headers["content-type"].lower(), got.headers["content-type"]
    assert "Світлана".encode("utf-8") in got.content


def test_место_приходит_текстом_с_координатами(point):
    me = point.sign_in(sub="place-in")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    sent = point.client.post("/u/" + box, data={"text": "50.450100, 30.523400", "name": "Место.txt"})

    assert sent.status_code == 200, sent.text
    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert b"50.450100" in got.content


def test_имя_присланного_не_выдумывается(point):
    """Без имени текст остаётся «Текстом» — как было."""
    me = point.sign_in(sub="plain-in")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    point.client.post("/u/" + box, data={"text": "просто текст"})

    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert "text/plain" in got.headers["content-type"]


def test_на_странице_приёма_есть_место_и_контакт(point):
    me = point.sign_in(sub="tabs-in")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert "Контакт" in html, "контакт прислать нечем"
    assert "Место" in html, "место прислать нечем"
    assert "geolocation" in html, "место не спрашивается у браузера"
    assert "BEGIN:VCARD" in html, "контакт не собирается в карточку"
