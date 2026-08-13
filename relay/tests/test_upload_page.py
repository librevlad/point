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


def test_без_разбора_формы_сервер_не_поднимается(monkeypatch):
    """Половина рабочего сервера хуже честно упавшего.

    Живой отказ 2026-08-10: python-multipart не был установлен, приём файла отвечал 500 на
    КАЖДУЮ загрузку, а /health отдавал 200 — сервер выглядел здоровым, и причину искали в
    интернете, в форме, в чём угодно.
    """
    import builtins

    import pytest

    from point_server import app as app_mod

    real_import = builtins.__import__

    def no_multipart(name, *a, **kw):
        if name in ("multipart", "python_multipart"):
            raise ModuleNotFoundError("нет такого модуля")
        return real_import(name, *a, **kw)

    monkeypatch.setattr(builtins, "__import__", no_multipart)
    with pytest.raises(RuntimeError) as boom:
        app_mod.create_app()

    assert "python-multipart" in str(boom.value)
    assert "requirements.txt" in str(boom.value), "отказ обязан сказать, что делать"


def test_после_отправки_страница_больше_ничего_не_принимает(point):
    """Отправив текст, чужой человек жал «Контакт»: вкладка переключалась, поля появлялись,
    внизу обещание — а кнопки отправки не было (#928). «Готово» при этом стиралось, и
    человек видел живую форму, которая молчит.

    Решение владельца 13.08.2026: «Одна отправка, и страница закрывается».
    """
    me = point.sign_in(sub="onesend")
    box = point.as_device(me["device_token"], "POST", "/u/open").json()["box"]

    html = point.client.get("/u/" + box).text

    assert "var sent=false" in html, "страница не помнит, что отправка уже была"
    assert "if(sent) return;" in html, "вкладки продолжают переключаться после отправки"
    assert "sent=true;" in html, "удачная отправка не закрывает страницу"
    assert "попросите новую ссылку" in html, "человеку не сказали, как прислать ещё"
