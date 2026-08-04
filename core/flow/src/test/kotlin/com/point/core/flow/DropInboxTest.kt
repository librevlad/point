package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Адрес ящика приёма (#388): его проверяют обе стороны, поэтому правило — не вкус, а тест. */
class DropInboxTest {

    @Test
    fun `twenty random bytes make a usable address`() {
        val id = dropInboxId(ByteArray(20) { it.toByte() })
        assertEquals(27, id.length) // 160 бит base64url без добивки
        assertTrue(id, isDropInboxId(id))
    }

    /** Адрес едет в URL: символов, которые пришлось бы экранировать, в нём быть не должно. */
    @Test
    fun `the address is url-safe`() {
        val id = dropInboxId(ByteArray(20) { (it * 37 + 251).toByte() })
        assertFalse(id, id.contains('+') || id.contains('/') || id.contains('='))
    }

    @Test
    fun `short or foreign addresses are refused`() {
        assertFalse(isDropInboxId("short"))
        assertFalse(isDropInboxId("a".repeat(21)))
        assertTrue(isDropInboxId("a".repeat(22)))
        assertFalse(isDropInboxId("a".repeat(65)))
        assertFalse(isDropInboxId("../../etc/passwd-aaaaaaaaaaaa"))
        assertFalse(isDropInboxId("ящикящикящикящикящикящик"))
    }

    @Test
    fun `the link points at the receiving page`() {
        val id = "a".repeat(27)
        assertEquals("https://relay.example/u/$id", dropInboxLink("https://relay.example/", id))
        assertEquals("https://relay.example/u/$id", dropInboxLink(" https://relay.example ", id))
    }

    /** Имя даёт чужой человек: путь в нём — попытка выйти из своего каталога, а не имя. */
    @Test
    fun `a stranger cannot name a file into another directory`() {
        assertEquals("passwd", dropFileName("../../etc/passwd"))
        assertEquals("boot.ini", dropFileName("C:\\Windows\\boot.ini"))
        assertEquals("hidden", dropFileName("...hidden"))
        assertEquals("файл", dropFileName("../"))
        assertEquals("файл", dropFileName("   "))
    }

    /** Обычное имя не портится: «отчёт за июль.pdf» обязан остаться собой. */
    @Test
    fun `an ordinary name survives untouched`() {
        assertEquals("отчёт за июль.pdf", dropFileName("отчёт за июль.pdf"))
    }

    @Test
    fun `control characters and separators never reach the disk`() {
        assertEquals("отчет.pdf", dropFileName("от\nчет?.pdf"))
        assertTrue(dropFileName("a".repeat(300)).length <= 120)
    }

    /**
     * Ожидание, которое не может кончиться, — не ожидание, а обман (#114).
     *
     * Человек с выключенным Wi-Fi смотрел на «Ждём файл…» столько, сколько выдержит: круг ожидания
     * возвращал одно и то же и на «никто ничего не положил», и на «сети нет».
     */
    @Test
    fun `сеть, которая упала, названа словами — а не молчанием`() {
        assertEquals("Ждём файл…", receiveWaitStatus(0))
        // Один сорвавшийся круг — ещё не новость: сеть моргает.
        assertTrue(receiveWaitStatus(1).contains("Связь пропала"))
        assertTrue(receiveWaitStatus(2).contains("Связь пропала"))
        // Три подряд — уже состояние, и молчать о нём нельзя.
        assertTrue(receiveWaitStatus(3).contains("Нет связи"))
        assertTrue(receiveWaitStatus(30).contains("Нет связи"))
    }

    /** И вторая правда рядом с первой: ящик живёт сутки, значит ссылка не умерла вместе с сетью. */
    @Test
    fun `потеря сети не выдаётся за смерть ссылки`() {
        assertTrue(receiveWaitStatus(5), receiveWaitStatus(5).contains("Ссылка не потерялась"))
    }

    /** Без релея ссылки нет — и лучше её отсутствие, чем ссылка в никуда. */
    @Test
    fun `no relay means no link`() {
        assertNull(dropInboxLink("", "a".repeat(27)))
        assertNull(dropInboxLink("   ", "a".repeat(27)))
        assertNull(dropInboxLink("https://relay.example", "short"))
    }
}
