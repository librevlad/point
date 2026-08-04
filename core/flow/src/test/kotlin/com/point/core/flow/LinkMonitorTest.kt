package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Что монитор помнит между запусками (#451).
 *
 * Баг был не в словах экрана, а в памяти под ними: контакт жил в процессе и умирал вместе с ним,
 * поэтому вчерашний компьютер встречал человека фразой «ещё не связывались». Забытое — не «ни
 * разу», и путать их нельзя.
 */
class LinkMonitorTest {

    private val at = 1_700_000_000_000L

    /** Тот же журнал, что переживёт перезапуск, — только без Android под ним. */
    private class Log(private var stored: LinkMonitor.Contact? = null) : LinkLog {
        var writes = 0
        override fun read() = stored
        override fun write(contact: LinkMonitor.Contact) { stored = contact; writes++ }
        override fun clear() { stored = null }
    }

    @Test
    fun `услышанное записывается в журнал`() {
        val log = Log()
        val monitor = RememberingLinkMonitor(log) { at }

        monitor.heard(LinkPath.LAN)

        assertEquals(LinkMonitor.Contact(at, LinkPath.LAN), log.read())
        assertEquals(LinkMonitor.Contact(at, LinkPath.LAN), monitor.last.value)
    }

    @Test
    fun `новый запуск начинается с вчерашнего контакта, а не с чистого листа`() {
        val log = Log(LinkMonitor.Contact(at, LinkPath.RELAY))

        // Приложение перезапустили: монитор собран заново, память — та же.
        val monitor = RememberingLinkMonitor(log) { at }

        assertEquals(LinkMonitor.Contact(at, LinkPath.RELAY), monitor.last.value)
    }

    @Test
    fun `журнал не читается, пока о нём не спросили`() {
        // Первый экран — 300 мс без I/O, а монитор собирается вместе с графом. Читать хранилище
        // в конструкторе значит платить за экран связи на каждом запуске.
        val log = object : LinkLog {
            var reads = 0
            override fun read(): LinkMonitor.Contact? { reads++; return null }
            override fun write(contact: LinkMonitor.Contact) = Unit
            override fun clear() = Unit
        }

        val monitor = RememberingLinkMonitor(log)
        assertEquals(0, log.reads)

        monitor.last.value
        assertEquals(1, log.reads)
    }

    @Test
    fun `отвязали компьютер — память о связи с ним уходит вместе с ним`() {
        // Иначе вчерашний компьютер рассказывал бы о следующем чужую правду.
        val log = Log(LinkMonitor.Contact(at, LinkPath.LAN))
        val monitor = RememberingLinkMonitor(log) { at }

        monitor.forget()

        assertNull(monitor.last.value)
        assertNull(log.read())
    }

    @Test
    fun `забывчивый журнал не помнит ничего между запусками`() {
        val monitor = RememberingLinkMonitor(ForgetfulLinkLog()) { at }
        monitor.heard(LinkPath.LAN)
        assertEquals(LinkMonitor.Contact(at, LinkPath.LAN), monitor.last.value)

        // Новый монитор над новым забывчивым журналом — то самое «ни разу».
        assertNull(RememberingLinkMonitor(ForgetfulLinkLog()).last.value)
    }
}
