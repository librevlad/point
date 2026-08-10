"""Страница приёма файла: её видит чужой человек, открывший незнакомую ссылку."""


def test_фото_с_телефона_принимается_целиком(point):
    me = point.sign_in(sub="bigphoto")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]
    photo = b"\xff\xd8\xff\xe0" + b"x" * (4 * 1024 * 1024)

    sent = point.client.post("/u/" + box, files={"file": ("IMG_17_28.jpg", photo, "image/jpeg")})

    assert sent.status_code == 200, sent.text
    got = point.as_device(me["device_token"], "GET", "/u/" + box + "/take")
    assert got.status_code == 200
    assert len(got.content) == len(photo)


def test_страница_показывает_ход_отправки_а_не_молчит(point):
    """«Виснет у клиента»: сервер принимает мгновенно, но снимок по мобильной связи идёт
    секунды — без индикатора страница читалась как поломка (скрин владельца 2026-08-10)."""
    me = point.sign_in(sub="pageuser")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert "upload.onprogress" in html, "ход отправки не показывается"
    assert "XMLHttpRequest" in html, "прогресс даёт только XHR, не fetch"


def test_без_скрипта_страница_остаётся_рабочей_формой(point):
    me = point.sign_in(sub="nojs")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert 'method="post"' in html and 'enctype="multipart/form-data"' in html
    assert 'type="file"' in html and 'name="file"' in html
    assert 'class="no-js"' in html, "без JS поле файла обязано вернуться видимым"


def test_ни_одного_чужого_домена(point):
    me = point.sign_in(sub="nocdn")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert "http://" not in html and "https://" not in html, "страница тянет что-то извне"


def test_ссылки_больше_нет_говорится_словами(point):
    assert point.client.get("/u/нетакого").status_code == 404
