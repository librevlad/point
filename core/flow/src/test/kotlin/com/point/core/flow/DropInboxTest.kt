package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropInboxTest {

    @Test
    fun `twenty random bytes make a usable address`() {
        val id = dropInboxId(ByteArray(20) { it.toByte() })
        assertEquals(27, id.length)
        assertTrue(id, isDropInboxId(id))
    }

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

    @Test
    fun `a stranger cannot name a file into another directory`() {
        assertEquals("passwd", dropFileName("../../etc/passwd"))
        assertEquals("boot.ini", dropFileName("C:\\Windows\\boot.ini"))
        assertEquals("hidden", dropFileName("...hidden"))
        assertEquals("файл", dropFileName("../"))
        assertEquals("файл", dropFileName("   "))
    }

    @Test
    fun `an ordinary name survives untouched`() {
        assertEquals("отчёт за июль.pdf", dropFileName("отчёт за июль.pdf"))
    }

    @Test
    fun `control characters and separators never reach the disk`() {
        assertEquals("от чет.pdf", dropFileName("от\nчет?.pdf"))
        assertTrue(dropFileName("a".repeat(300)).length <= 120)
    }

    @Test
    fun `сеть, которая упала, названа словами — а не молчанием`() {
        assertEquals("Ждём файл…", receiveWaitStatus(0))

        assertTrue(receiveWaitStatus(1).contains("Связь пропала"))
        assertTrue(receiveWaitStatus(2).contains("Связь пропала"))

        assertTrue(receiveWaitStatus(3).contains("Нет связи"))
        assertTrue(receiveWaitStatus(30).contains("Нет связи"))
    }

    @Test
    fun `потеря сети не выдаётся за смерть ссылки`() {
        assertTrue(receiveWaitStatus(5), receiveWaitStatus(5).contains("Ссылка не потерялась"))
    }

    @Test
    fun `no relay means no link`() {
        assertNull(dropInboxLink("", "a".repeat(27)))
        assertNull(dropInboxLink("   ", "a".repeat(27)))
        assertNull(dropInboxLink("https://relay.example", "short"))
    }
}
