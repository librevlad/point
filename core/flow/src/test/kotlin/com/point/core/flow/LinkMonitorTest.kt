package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMonitorTest {

    private val at = 1_700_000_000_000L

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

        monitor.heard()

        assertEquals(LinkMonitor.Contact(at), log.read())
        assertEquals(LinkMonitor.Contact(at), monitor.last.value)
    }

    @Test
    fun `новый запуск начинается с вчерашнего контакта, а не с чистого листа`() {
        val log = Log(LinkMonitor.Contact(at))

        val monitor = RememberingLinkMonitor(log) { at }

        assertEquals(LinkMonitor.Contact(at), monitor.last.value)
    }

    @Test
    fun `журнал не читается, пока о нём не спросили`() {

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

        val log = Log(LinkMonitor.Contact(at))
        val monitor = RememberingLinkMonitor(log) { at }

        monitor.forget()

        assertNull(monitor.last.value)
        assertNull(log.read())
    }

    @Test
    fun `забывчивый журнал не помнит ничего между запусками`() {
        val monitor = RememberingLinkMonitor(ForgetfulLinkLog()) { at }
        monitor.heard()
        assertEquals(LinkMonitor.Contact(at), monitor.last.value)

        assertNull(RememberingLinkMonitor(ForgetfulLinkLog()).last.value)
    }
}
