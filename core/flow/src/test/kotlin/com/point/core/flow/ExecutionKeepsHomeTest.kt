package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дом объекта и место исполнения — разные вещи (ADR-0001 §7, §20).
 *
 * Сосед, который умеет нужное, — исполнитель, а не новый дом. К нему уезжает работа, а
 * результат возвращается туда, где с объектом работает человек, — и приходит не сиротой:
 * из чего сделан, чем сделан и каким путём, видно и на той стороне.
 */
class ExecutionKeepsHomeTest {

    private val arrived = PointObject(
        id = "приехавшее",
        mime = "application/pdf",
        uri = ScratchRef("/scratch/out.pdf"),
        state = ObjectState(ObjectKind.PDF),
    )

    @Test
    fun `родословная переживает письмо между устройствами`() {
        val meta = lineageMeta(sourceId = "дома", creator = "office-pdf", provenance = Provenance.RULE)

        val landed = withLineage(arrived, meta)

        assertEquals(listOf("дома"), landed.sourceObjects)
        assertEquals("office-pdf", landed.creatorAction)
        assertEquals(Provenance.RULE, landed.provenance)
    }

    @Test
    fun `без родословной объект остаётся собой, а не портится`() {
        val landed = withLineage(arrived, emptyMap())

        assertEquals(arrived, landed)
    }

    @Test
    fun `результат работы соседа не приезжает как присланный человеком`() {
        val meta = lineageMeta(sourceId = "дома", creator = "cutout", provenance = Provenance.MODEL)

        val landed = withLineage(arrived, meta)

        assertNotEquals("объект, сделанный действием, не «дано»", Provenance.GIVEN, landed.provenance)
        assertTrue("след действия потерян", landed.creatorAction != null)
    }

    @Test
    fun `служебные поля просьбы знанием об объекте не становятся`() {
        val ask = mapOf(
            PcExecFields.HOME to "дома",
            PcExecFields.ACTION to "cutout",
            PcExecFields.REQUEST to "r-1",
            META_ENTITY_PREFIX + "amount" to "7800 UAH",
        )

        val knowledge = ask - PC_EXEC_META

        assertEquals(setOf(META_ENTITY_PREFIX + "amount"), knowledge.keys)
    }

    @Test
    fun `имя исполнителя едет вместе с родословной`() {
        val meta = lineageMeta("дома", "cutout", Provenance.RULE, executor = "phone")

        assertEquals("phone", meta[PcExecFields.BY])
    }
}
