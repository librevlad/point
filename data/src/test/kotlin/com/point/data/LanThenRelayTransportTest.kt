package com.point.data

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Безотказно» = LAN when it can be reached, the relay whenever it can't (#161 v2). */
class LanThenRelayTransportTest {

    private open class Fake(val label: String) : PcTransport {
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?): PcSendOutcome =
            PcSendOutcome.Unreachable(label)
        override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean = false
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) = Unit
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean = false
    }

    private fun obj() = PointObject("id", "image/jpeg", ScratchRef("/x"), ObjectState(ObjectKind.IMAGE))
    private val noRelay = PcPairing("192.168.1.242", 8391, "tok")
    private val withRelay = PcPairing("192.168.1.242", 8391, "tok", relay = "https://s:8443")

    @Test
    fun `lan success never touches the relay`() = runTest {
        var relayCalled = false
        val lan = object : Fake("lan") {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) = PcSendOutcome.Sent
        }
        val relay = object : Fake("relay") {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?): PcSendOutcome {
                relayCalled = true; return PcSendOutcome.Sent
            }
        }
        assertEquals(PcSendOutcome.Sent, LanThenRelayTransport(lan, relay).send(withRelay, obj(), "f", emptyMap(), null))
        assertFalse("relay must not be used when LAN works", relayCalled)
    }

    @Test
    fun `lan unreachable with a relay falls back to the relay`() = runTest {
        val relay = object : Fake("relay") {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) = PcSendOutcome.Sent
        }
        assertEquals(PcSendOutcome.Sent, LanThenRelayTransport(Fake("lan"), relay).send(withRelay, obj(), "f", emptyMap(), null))
    }

    @Test
    fun `lan unreachable without a relay stays unreachable`() = runTest {
        val r = LanThenRelayTransport(Fake("lan"), Fake("relay")).send(noRelay, obj(), "f", emptyMap(), null)
        assertTrue(r is PcSendOutcome.Unreachable)
    }

    @Test
    fun `lan rejected does not fall back — a token problem is not a reachability problem`() = runTest {
        var relayCalled = false
        val lan = object : Fake("lan") {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) = PcSendOutcome.Rejected
        }
        val relay = object : Fake("relay") {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?): PcSendOutcome {
                relayCalled = true; return PcSendOutcome.Sent
            }
        }
        assertEquals(PcSendOutcome.Rejected, LanThenRelayTransport(lan, relay).send(withRelay, obj(), "f", emptyMap(), null))
        assertFalse(relayCalled)
    }
}
