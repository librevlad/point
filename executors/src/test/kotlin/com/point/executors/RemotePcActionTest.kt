package com.point.executors

import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.flow.PcRemoteAction
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

/** Remote PC capabilities (#80): the PC's own actions appear as phone bubbles and run there. */
class RemotePcActionTest {

    private val action = PcRemoteAction("pc-open", "Открыть на компьютере")

    private class FakePairings(var pairing: PcPairing? = PcPairing("192.168.1.2", 8391, "tok")) : PcPairings {
        override fun current() = pairing
        override suspend fun save(pairing: PcPairing) { this.pairing = pairing }
        override suspend fun clear() { pairing = null }
    }

    private class FakeTransport(var outcome: PcSendOutcome = PcSendOutcome.Sent) : PcTransport {
        var sentAction: String? = null
        override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? = null
        override suspend fun send(
            pairing: PcPairing,
            obj: PointObject,
            fileName: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            sentAction = action
            return outcome
        }
        override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? = null
    }

    private fun obj() = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    @Test
    fun `visible only when paired, never for collections, carries the PC label`() {
        val paired = RemotePcCapability(action, FakePairings())
        assertTrue(paired.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(paired.accepts(ObjectState(ObjectKind.COLLECTION)))
        assertEquals("Открыть на компьютере", paired.label(ObjectState(ObjectKind.TEXT)))

        val unpaired = RemotePcCapability(action, FakePairings(pairing = null))
        assertFalse(unpaired.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `a kind-gated action appears only for its kinds (#80 v2)`() {
        val urlOnly = RemotePcCapability(PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")), FakePairings())
        assertTrue(urlOnly.accepts(ObjectState(ObjectKind.URL)))
        assertFalse(urlOnly.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `sends the object with the action id and reports success`() = runTest {
        val transport = FakeTransport()
        val result = RemotePcRealizer(action, FakePairings(), transport).perform(obj(), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("pc-open", transport.sentAction)
    }

    @Test
    fun `a rejected token asks to re-pair, unreachable is recoverable`() = runTest {
        val rejected = RemotePcRealizer(action, FakePairings(), FakeTransport(PcSendOutcome.Rejected))
            .perform(obj(), null)
        assertTrue((rejected as ActionResult.Failure).reason.contains("заново"))

        val gone = RemotePcRealizer(action, FakePairings(), FakeTransport(PcSendOutcome.Unreachable("timeout")))
            .perform(obj(), null)
        assertTrue((gone as ActionResult.Failure).recoverable)
    }
}
