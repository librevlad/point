# -*- coding: utf-8 -*-
"""Ящик ждёт письма на сервере, а не заставляет спрашивать каждые две секунды (#1400)."""
import threading
import time


def _pair(point):
    phone = point.sign_in(sub="wait")
    pc = point.sign_in(sub="wait", kind="PC", device_name="ПК")
    return phone, pc


def test_без_wait_пустой_ящик_отвечает_сразу_как_прежде(point):
    _, pc = _pair(point)

    started = time.monotonic()
    got = point.as_device(pc["device_token"], "GET", "/mbx/" + pc["device_id"])

    assert got.status_code == 204
    assert time.monotonic() - started < 1


def test_с_wait_пустой_ящик_держит_вопрос_до_срока(point):
    _, pc = _pair(point)

    started = time.monotonic()
    got = point.as_device(pc["device_token"], "GET", "/mbx/" + pc["device_id"], params={"wait": 2})

    assert got.status_code == 204
    assert time.monotonic() - started >= 1.9


def test_письмо_уходит_ждущему_в_тот_же_миг(point):
    phone, pc = _pair(point)

    def send_later():
        time.sleep(0.5)
        point.as_device(phone["device_token"], "POST", "/mbx/" + pc["device_id"], content=b"letter")

    threading.Thread(target=send_later).start()
    started = time.monotonic()
    got = point.as_device(pc["device_token"], "GET", "/mbx/" + pc["device_id"], params={"wait": 10})

    assert got.status_code == 200
    assert got.content == b"letter"
    assert time.monotonic() - started < 3


def test_срок_ожидания_ограничен_сервером(point):
    from point_server import app as app_mod

    assert app_mod.MAX_MAILBOX_WAIT <= 30
