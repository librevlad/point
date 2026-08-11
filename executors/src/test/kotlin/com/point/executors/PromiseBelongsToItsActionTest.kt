package com.point.executors

import com.point.core.flow.yieldLabel
import com.point.core.model.ActionYield
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ни одно действие не обещает чужими словами (#734).
 *
 * Подпись была привязана к типу исхода: «объект тот же» отдавал одну и ту же фразу всем,
 * кто его объявлял. Написана она была под «Понять», а доставалась ещё и «Исправить ошибки»
 * с «Исправить сильнее» — те правят опечатки распознавания и никаких сумм с контактами не
 * ищут. Человеку обещали одно, делали другое.
 */
class PromiseBelongsToItsActionTest {

    private val image = ObjectState(ObjectKind.IMAGE)

    private val ready = com.point.core.flow.AiReadiness { true }

    private val understand = UnderstandCapability(ready)

    private val fixes = listOf(FixErrorsCapability(ready), FixErrorsStrongerCapability(ready))

    @Test
    fun `«Понять» сохраняет свою строку`() {
        val said = yieldLabel(understand.yields(image))

        assertEquals(UnderstandCapability.UNDERSTAND_NOTE, said)
    }

    @Test
    fun `правка опечаток не обещает найти суммы и контакты`() {
        fixes.forEach { fix ->
            val said = yieldLabel(fix.yields(image))

            assertNotEquals("«${fix.label(image)}» обещает словами «Понять»", UnderstandCapability.UNDERSTAND_NOTE, said)
            assertNull("«${fix.label(image)}» договаривает то, чего не делает: $said", said)
        }
    }

    /**
     * Третья приёмка карточки: подпись не может достаться новому действию молча. Слова живут
     * в самом исходе, поэтому объявить «объект тот же» и получить чужое обещание нечем —
     * проверяется прямо на пустом исходе, а не на списке известных способностей.
     */
    @Test
    fun `новое действие с тем же исходом не наследует чужое обещание`() {
        assertNull(yieldLabel(ActionYield.Same()))
    }

    @Test
    fun `обещание «Понять» говорит о результате, а не о механике`() {
        val said = UnderstandCapability.UNDERSTAND_NOTE

        assertTrue("подпись снова про механику: $said", "объект тот же" !in said)
        assertTrue(
            "подпись не называет ничего из того, что человек получит: $said",
            listOf("суть", "суммы", "даты", "контакты").any { it in said },
        )
    }
}
