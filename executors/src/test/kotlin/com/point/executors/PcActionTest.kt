package com.point.executors

import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «На компьютер» (#147): hidden without a pairing (with a latent hint), terminal,
 *  and every transport outcome maps to an honest ActionResult. */
class PcActionTest {

    private class FakePairings(var pairing: PcPairing? = null) : PcPairings {
        override fun current() = pairing
        override suspend fun save(pairing: PcPairing) { this.pairing = pairing }
        override suspend fun clear() { pairing = null }
    }

    private class FakeTransport(var outcome: PcSendOutcome = PcSendOutcome.Sent) : PcTransport {
        var sentMeta: Map<String, String>? = null
        var sentName: String? = null
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun send(
            pairing: PcPairing, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?,
        ): PcSendOutcome {
            sentName = fileName
            sentMeta = meta
            return outcome
        }
        override suspend fun fetchCaps(pairing: PcPairing): List<com.point.core.flow.PcRemoteAction>? = null
        override suspend fun fetchOutbox(pairing: PcPairing): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean = false
        override suspend fun ackOutbox(pairing: PcPairing, id: Int) {}
        override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<com.point.core.flow.PcRemoteAction>): Boolean = true
    }

    private fun obj(meta: Map<String, String> = emptyMap()) = PointObject(
        id = "o", mime = "image/jpeg", uri = ScratchRef("/tmp/x.jpg"),
        state = ObjectState(ObjectKind.IMAGE), metadata = meta,
    )

    @Test
    fun `hidden without a pairing but leaves a latent hint`() {
        val cap = PcCapability(FakePairings(null))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertEquals("подключите компьютер", cap.missing(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `visible with a pairing and terminal`() {
        val cap = PcCapability(FakePairings(PcPairing("h", 1, "t")))
        val state = ObjectState(ObjectKind.IMAGE)
        assertTrue(cap.accepts(state))
        assertTrue(cap.produces(state) === state)
    }

    @Test
    fun `sends the object's understanding along and reports Done`() = runTest {
        val transport = FakeTransport()
        val realizer = PcRealizer(FakePairings(PcPairing("h", 1, "t")), transport)

        val result = realizer.perform(obj(mapOf("name" to "чек.jpg", "entity.phone" to "+3806")), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("чек.jpg", transport.sentName)
        assertEquals("+3806", transport.sentMeta!!["entity.phone"])
    }

    @Test
    fun `rejection asks to re-pair, unreachable stays recoverable`() = runTest {
        val pairings = FakePairings(PcPairing("h", 1, "t"))
        val transport = FakeTransport(PcSendOutcome.Rejected)
        val rejected = PcRealizer(pairings, transport).perform(obj(), null)
        assertTrue((rejected as ActionResult.Failure).reason.contains("заново"))

        transport.outcome = PcSendOutcome.Unreachable("timeout")
        val unreachable = PcRealizer(pairings, transport).perform(obj(), null)
        assertTrue((unreachable as ActionResult.Failure).recoverable)
    }
}
