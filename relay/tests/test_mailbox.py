# -*- coding: utf-8 -*-
"""Ящики под аккаунтом (#476): чужое не видно, переполнение сказано вслух, старое исчезает само."""
import os
import time

import pytest

from point_server import mailbox


@pytest.fixture()
def root(tmp_path):
    return str(tmp_path)


def test_почта_кладётся_и_забирается_подтверждением(root):
    bid = mailbox.push(root, "u1", "dev1", b"hello")

    got = mailbox.pull(root, "u1", "dev1")
    assert got is not None
    assert got[1] == b"hello"

    # Выдача НЕ удаляет: на разрыве связи письмо потерялось бы молча.
    assert mailbox.pull(root, "u1", "dev1") is not None
    assert mailbox.ack(root, "u1", "dev1", bid) is True
    assert mailbox.pull(root, "u1", "dev1") is None


def test_чужая_почта_не_видна(root):
    mailbox.push(root, "u1", "dev1", "моё".encode("utf-8"))

    # Тот же адрес устройства у другого человека — другой ящик. Владелец входит в путь, а не
    # проверяется отдельно, поэтому запроса к чужому ящику не существует как выражения.
    assert mailbox.pull(root, "u2", "dev1") is None


def test_ссылка_находится_без_владельца_а_чужой_ящик_нет(root):
    did = mailbox.drop_put(root, "u1", "файл".encode("utf-8"), "отчёт.pdf", "application/pdf")

    # Ссылку открывает чужой браузер, пропуска у него нет — ищем без владельца. Это названное
    # исключение из слепоты, и оно ограничено ссылками.
    found = mailbox.drop_find(root, did)
    assert found is not None and found[1] == "отчёт.pdf"

    # А вот забрать присланное в ящик приёма можно только под своим пропуском.
    box = mailbox.inbox_open(root, "u1")
    mailbox.inbox_accept(mailbox.inbox_find(root, box), "файл".encode("utf-8"), "скан.jpg", "image/jpeg")
    assert mailbox.inbox_take(root, "u2", box) is None
    assert mailbox.inbox_take(root, "u1", box) is not None


def test_переполнение_говорит_словами_а_не_молчит(root):
    with pytest.raises(mailbox.Full) as e:
        mailbox.push(root, "u1", "dev1", b"x" * (mailbox.MAX_BLOB + 1))
    assert "50 МБ" in str(e.value)

    for _ in range(mailbox.MAX_DROPS):
        mailbox.drop_put(root, "u1", b"x", "f", "text/plain")
    with pytest.raises(mailbox.Full) as e:
        mailbox.drop_put(root, "u1", b"x", "f", "text/plain")
    assert "20" in str(e.value)

    for _ in range(mailbox.MAX_INBOXES):
        mailbox.inbox_open(root, "u1")
    with pytest.raises(mailbox.Full):
        mailbox.inbox_open(root, "u1")


def test_старое_исчезает_само_а_свежее_остаётся(root):
    old = mailbox.drop_put(root, "u1", "вчерашний".encode("utf-8"), "f", "text/plain")
    fresh = mailbox.drop_put(root, "u1", "сегодняшний".encode("utf-8"), "f", "text/plain")

    # Состарим первый на двое суток.
    box = os.path.join(mailbox.drops_dir(root, "u1"), old)
    stale = time.time() - 2 * mailbox.TTL_SECONDS
    os.utime(box, (stale, stale))

    assert mailbox.sweep(root) >= 1
    assert mailbox.drop_find(root, old) is None
    assert mailbox.drop_find(root, fresh) is not None


def test_уход_человека_стирает_его_байты(root):
    mailbox.push(root, "u1", "dev1", "письмо".encode("utf-8"))
    did = mailbox.drop_put(root, "u1", "файл".encode("utf-8"), "f", "text/plain")
    mailbox.push(root, "u2", "dev1", "чужое письмо".encode("utf-8"))

    mailbox.forget_user(root, "u1")

    assert mailbox.pull(root, "u1", "dev1") is None
    assert mailbox.drop_find(root, did) is None
    # Соседа это не задело.
    assert mailbox.pull(root, "u2", "dev1") is not None


def test_ящик_закрывается_и_освобождает_предел(root):
    """
    Пять открытий экрана «Принять файл» за сутки не должны выключать приём до утра (#729).

    Ящик убирала только суточная уборка, поэтому предел выбирался обычным использованием,
    а не злоупотреблением.
    """
    boxes = [mailbox.inbox_open(root, "u1") for _ in range(mailbox.MAX_INBOXES)]
    with pytest.raises(mailbox.Full):
        mailbox.inbox_open(root, "u1")

    assert mailbox.inbox_close(root, "u1", boxes[0]) is True
    assert mailbox.inbox_find(root, boxes[0]) is None

    # Место освободилось — дверь снова открывается.
    mailbox.inbox_open(root, "u1")


def test_закрыть_можно_только_свой_ящик(root):
    box = mailbox.inbox_open(root, "u1")

    assert mailbox.inbox_close(root, "u2", box) is False
    assert mailbox.inbox_find(root, box) is not None

    assert mailbox.inbox_close(root, "u1", "нет-такого") is False
