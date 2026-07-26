package com.point.data

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A shared/OCR'd `point-pc://` text lights the pairing feature (#147 slice C). */
class PcPairingEnricherTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun textObj(content: String): PointObject {
        val f = File(tmp.root, "t.txt").apply { writeText(content) }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `a pairing payload lights the feature and stores the payload`() = runTest {
        val delta = PcPairingEnricher().enrich(textObj("point-pc://192.168.1.42:8391/abc123"))
        assertEquals(setOf(Feature.HAS_PC_PAIRING), delta.features)
        assertEquals("point-pc://192.168.1.42:8391/abc123", delta.metadata["pc.pairing"])
    }

    @Test
    fun `plain text stays silent`() = runTest {
        val delta = PcPairingEnricher().enrich(textObj("просто заметка"))
        assertTrue(delta.features.isEmpty())
    }
}
