package com.point.executors

import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** «Подключить компьютер» from a pairing text (#147): photograph the PC's QR → read-qr →
 *  this bubble — pairing without any camera code. */
class PairPcActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakePairings : PcPairings {
        var saved: PcPairing? = null
        override fun current() = saved
        override suspend fun save(pairing: PcPairing) { saved = pairing }
        override suspend fun clear() { saved = null }
    }

    @Test
    fun `accepts only text with the pairing feature`() {
        val cap = PairPcCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PC_PAIRING))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `saves the pairing from metadata and confirms`() = runTest {
        val pairings = FakePairings()
        val obj = PointObject(
            "id", "text/plain", ScratchRef("/x"),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PC_PAIRING)),
            metadata = mapOf("pc.pairing" to "point-pc://192.168.1.42:8391/tok"),
        )

        val result = PairPcRealizer(pairings).perform(obj, null)

        assertTrue(result is ActionResult.Done)
        assertEquals(PcPairing("192.168.1.42", 8391, "tok"), pairings.saved)
    }

    @Test
    fun `falls back to reading the file when metadata is absent`() = runTest {
        val pairings = FakePairings()
        val f = File(tmp.root, "qr.txt").apply { writeText("point-pc://10.0.2.2:8392/abc") }
        val obj = PointObject(
            "id", "text/plain", ScratchRef(f.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PC_PAIRING)),
        )

        PairPcRealizer(pairings).perform(obj, null)

        assertEquals(PcPairing("10.0.2.2", 8392, "abc"), pairings.saved)
    }
}
