package com.point.data

import com.point.core.flow.DiscoveredPc
import com.point.core.flow.PcDiscovery
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Self-healing LAN (#161 v2): a stale saved IP re-resolves via mDNS and retries with the token. */
class SelfHealingPcTransportTest {

    private open class Fake : PcTransport {
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?): PcSendOutcome =
            PcSendOutcome.Unreachable("fake")
        override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean = false
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) = Unit
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean = false
    }

    private class CapturingPairings(private var value: PcPairing?) : PcPairings {
        var saved: PcPairing? = null
        override fun current(): PcPairing? = value
        override suspend fun save(pairing: PcPairing) { saved = pairing; value = pairing }
        override suspend fun clear() { value = null }
    }

    private fun obj() = PointObject("id", "image/jpeg", ScratchRef("/x"), ObjectState(ObjectKind.IMAGE))
    private val stale = PcPairing("10.0.0.5", 8391, "tok")

    @Test
    fun `heal candidates drop the current dead address`() {
        val discovered = listOf(DiscoveredPc("pc", "10.0.0.5", 8391), DiscoveredPc("pc", "10.0.0.9", 8391))
        assertEquals(listOf(DiscoveredPc("pc", "10.0.0.9", 8391)), pcHealCandidates(stale, discovered))
    }

    @Test
    fun `lan success is returned as-is, no discovery`() = runTest {
        var scanned = false
        val lan = object : Fake() {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) = PcSendOutcome.Sent
        }
        val discovery = PcDiscovery { scanned = true; flowOf(emptyList()) }
        val pairings = CapturingPairings(stale)
        val outcome = SelfHealingPcTransport(lan, discovery, pairings).send(stale, obj(), "f", emptyMap(), null)
        assertEquals(PcSendOutcome.Sent, outcome)
        assertFalse("no re-resolve when LAN works", scanned)
        assertNull(pairings.saved)
    }

    @Test
    fun `a transient hiccup on the current address is retried once, without re-resolving`() = runTest {
        var calls = 0
        var scanned = false
        val lan = object : Fake() {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) =
                if (++calls == 1) PcSendOutcome.Unreachable("hiccup") else PcSendOutcome.Sent
        }
        val discovery = PcDiscovery { scanned = true; flowOf(emptyList()) }
        val pairings = CapturingPairings(stale)

        val outcome = SelfHealingPcTransport(lan, discovery, pairings).send(stale, obj(), "f", emptyMap(), null)

        assertEquals(PcSendOutcome.Sent, outcome)
        assertEquals(2, calls)                     // retried once on the same address
        assertFalse("no re-resolve when the retry succeeds", scanned)
        assertNull(pairings.saved)                 // same address — nothing new to remember
    }

    @Test
    fun `on unreachable, re-resolves via mDNS, retries with the token, and remembers the working address`() = runTest {
        val live = "10.0.0.9"
        val lan = object : Fake() {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) =
                if (pairing.host == live) PcSendOutcome.Sent else PcSendOutcome.Unreachable("stale")
        }
        val discovery = PcDiscovery { flowOf(listOf(DiscoveredPc("pc", live, 8391))) }
        val pairings = CapturingPairings(stale)

        val outcome = SelfHealingPcTransport(lan, discovery, pairings).send(stale, obj(), "f", emptyMap(), null)

        assertEquals(PcSendOutcome.Sent, outcome)
        assertEquals(live, pairings.saved?.host)      // healed address remembered
        assertEquals("tok", pairings.saved?.token)    // same token — same PC
    }

    @Test
    fun `a rejected token is never healed — that is a re-pair signal, not a stale address`() = runTest {
        var scanned = false
        val lan = object : Fake() {
            override suspend fun send(pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?) = PcSendOutcome.Rejected
        }
        val discovery = PcDiscovery { scanned = true; flowOf(emptyList()) }
        val pairings = CapturingPairings(stale)
        val outcome = SelfHealingPcTransport(lan, discovery, pairings).send(stale, obj(), "f", emptyMap(), null)
        assertEquals(PcSendOutcome.Rejected, outcome)
        assertFalse("Rejected is not a reachability failure", scanned)
        assertNull(pairings.saved)
    }
}
