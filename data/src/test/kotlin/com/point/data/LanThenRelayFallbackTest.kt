package com.point.data

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.PointObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Когда телефон идёт через релей, а когда нет (#161).
 *
 * До этой правки через релей ходила ровно одна операция — отправка объекта. Всё остальное было
 * привязано к локальной сети, и у человека с изоляцией клиентов на роутере связь работала на одну
 * шестую: спариться нельзя, узнать про принтер нельзя, забрать сделанное нельзя.
 *
 * Здесь судится само правило переключения — оно и есть сердце связи.
 */
class LanThenRelayFallbackTest {

    private val pairingWithRelay = PcPairing("192.168.1.5", 8391, "token", relay = "https://relay")
    private val pairingNoRelay = PcPairing("192.168.1.5", 8391, "token", relay = null)

    private val caps = listOf(PcRemoteAction("pc-print", "Напечатать на ПК"))
    private val entries = listOf(PcOutboxEntry(1, mapOf("name" to "отчёт.pdf")))

    /** Мёртвая локальная сеть: ровно то, что даёт роутер с изоляцией клиентов. */
    private class DeadLan : PcTransport {
        override suspend fun send(p: PcPairing, o: PointObject, f: String, m: Map<String, String>, a: String?) =
            PcSendOutcome.Unreachable("нет сети")
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) = Unit
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>) = false
    }

    /** Живая локальная сеть: релей в этом случае трогать нельзя — он медленнее и через облако. */
    private class LiveLan(
        private val caps: List<PcRemoteAction>,
        private val entries: List<PcOutboxEntry>,
    ) : PcTransport {
        override suspend fun send(p: PcPairing, o: PointObject, f: String, m: Map<String, String>, a: String?) =
            PcSendOutcome.Sent
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun fetchCaps(pairing: PcPairing) = caps
        override suspend fun fetchOutbox(pairing: PcPairing) = entries
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String) = true
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) = Unit
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>) = true
    }

    /** Считает, о чём спросили релей. */
    private class CountingRelay(
        private val caps: List<PcRemoteAction>? = null,
        private val entries: List<PcOutboxEntry>? = null,
    ) : PcTransport {
        val asked = mutableListOf<String>()
        override suspend fun send(p: PcPairing, o: PointObject, f: String, m: Map<String, String>, a: String?): PcSendOutcome {
            asked += "send"; return PcSendOutcome.Sent
        }
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? {
            asked += "pair"; return null
        }
        override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? {
            asked += "caps"; return caps
        }
        override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? {
            asked += "outbox"; return entries
        }
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean {
            asked += "fetch"; return true
        }
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) { asked += "ack" }
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean {
            asked += "phone-caps"; return true
        }
    }

    @Test
    fun `нет локальной сети — про возможности компьютера спрашиваем релей`() = runTest {
        val relay = CountingRelay(caps = caps)
        val transport = LanThenRelayTransport(DeadLan(), relay)

        assertEquals(caps, transport.fetchCaps(pairingWithRelay))
        assertTrue(relay.asked.contains("caps"))
    }

    @Test
    fun `нет локальной сети — очередь и файл тоже идут через релей`() = runTest {
        val relay = CountingRelay(entries = entries)
        val transport = LanThenRelayTransport(DeadLan(), relay)

        assertEquals(entries, transport.fetchOutbox(pairingWithRelay))
        assertTrue(transport.downloadOutboxFile(pairingWithRelay, 1, "/tmp/из-очереди.pdf"))
        assertTrue(relay.asked.containsAll(listOf("outbox", "fetch")))
    }

    @Test
    fun `локальная сеть жива — релей не трогаем, он медленнее и через облако`() = runTest {
        val relay = CountingRelay(caps = caps)
        val transport = LanThenRelayTransport(LiveLan(caps, entries), relay)

        transport.fetchCaps(pairingWithRelay)
        transport.fetchOutbox(pairingWithRelay)
        transport.downloadOutboxFile(pairingWithRelay, 1, "/tmp/x")
        transport.pushPhoneCaps(pairingWithRelay, caps)

        assertFalse("релей спросили зря: $relay", relay.asked.any { it != "ack" })
    }

    @Test
    fun `релея в пейринге нет — спрашивать некого, и это не падение`() = runTest {
        val relay = CountingRelay(caps = caps)
        val transport = LanThenRelayTransport(DeadLan(), relay)

        assertNull(transport.fetchCaps(pairingNoRelay))
        assertFalse(transport.downloadOutboxFile(pairingNoRelay, 1, "/tmp/x"))
        assertTrue("релей трогать не должны были: ${relay.asked}", relay.asked.isEmpty())
    }
}
