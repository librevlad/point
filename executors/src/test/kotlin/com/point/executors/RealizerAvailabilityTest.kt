package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealizerAvailabilityTest {

    private class Fake(
        id: String,
        private val available: Boolean = true,
        private val reason: String? = null,
        priority: Int = 50,
    ) : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(priority = priority)
        override fun isAvailable() = available
        override fun unavailableReason() = reason
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            error("не запускается в этой проверке")
    }

    private fun availability(vararg realizers: Realizer) = RealizerAvailability(realizers.toSet())

    @Test
    fun `выполнять нечем, и оно сказало почему`() {
        val blocked = availability(Fake("scan-plus", available = false, reason = "нужен пакет обработки снимков"))

        assertEquals("нужен пакет обработки снимков", blocked.blockerFor(CapabilityId("scan-plus")))
    }

    @Test
    fun `есть хотя бы один живой реализатор — мешать нечему`() {

        val chain = availability(
            Fake("scan", available = false, reason = "нужен пакет обработки снимков", priority = 10),
            Fake("scan", available = true, priority = 90),
        )

        assertNull(chain.blockerFor(CapabilityId("scan")))
    }

    @Test
    fun `молчащий гейт оставляет действие на экране`() {

        val silent = availability(Fake("ocr-cloud", available = false))

        assertNull(silent.blockerFor(CapabilityId("ocr-cloud")))
    }

    @Test
    fun `у способности без реализаторов ничего не выдумывается`() {

        assertNull(availability().blockerFor(CapabilityId("нет такого")))
    }

    @Test
    fun `объясняет себя предпочтительный реализатор, а не случайный`() {

        val two = availability(
            Fake("scan-plus", available = false, reason = "нужен пакет обработки снимков", priority = 10),
            Fake("scan-plus", available = false, reason = "второй голос", priority = 90),
        )

        assertEquals("нужен пакет обработки снимков", two.blockerFor(CapabilityId("scan-plus")))
    }

    @Test
    fun `«Скан с цветом» умеет объяснить свою тишину`() {

        val realizer = PageScanRealizer(ScanPlusCapability.ID, op = "scan-plus", store = FakeStore)

        assertEquals("нужен пакет обработки снимков", realizer.unavailableReason())
    }

    private object FakeStore : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) = com.point.core.model.ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }
}
