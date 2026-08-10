package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Годность — часть состояния объекта» (решение владельца, #684/#685): `Feature.UNUSABLE` +
 * `META_UNUSABLE_REASON` — один и тот же факт для экрана, подписи действия и Resolver'а,
 * а не проверка, разбросанная по каждому исполнителю.
 */
class ObjectFitnessTest {

    private fun obj(features: Set<Feature> = emptySet(), metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT, features), metadata)

    @Test
    fun `причина видна только когда объект и правда отмечен негодным`() {
        val marked = obj(setOf(Feature.UNUSABLE), mapOf(META_UNUSABLE_REASON to "Файл пустой"))

        assertEquals("Файл пустой", unusableReasonOf(marked.metadata))
    }

    @Test
    fun `пустая или пробельная причина не считается сказанной`() {
        assertNull(unusableReasonOf(emptyMap()))
        assertNull(unusableReasonOf(mapOf(META_UNUSABLE_REASON to "")))
        assertNull(unusableReasonOf(mapOf(META_UNUSABLE_REASON to "   ")))
    }

    @Test
    fun `GraphState отдаёт причину, только когда Feature действительно стоит`() {
        val withReasonButNoFeature = com.point.core.flow.GraphState(
            obj(emptySet(), mapOf(META_UNUSABLE_REASON to "Файл пустой")),
        )
        val marked = com.point.core.flow.GraphState(
            obj(setOf(Feature.UNUSABLE), mapOf(META_UNUSABLE_REASON to "Файл пустой")),
        )

        assertNull("знание без состояния — не считово", withReasonButNoFeature.unusableReason())
        assertEquals("Файл пустой", marked.unusableReason())
    }

    @Test
    fun `обычный объект без пометки не выдаёт причину`() {
        val ok = com.point.core.flow.GraphState(obj())

        assertNull(ok.unusableReason())
    }

    @Test
    fun `текст причины пустого файла — человеческий, без жаргона`() {
        assertTrue(EMPTY_FILE_REASON.isNotBlank())
        assertFalse(EMPTY_FILE_REASON.any { it in 'a'..'z' || it in 'A'..'Z' })
    }
}
