package com.point.executors

import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.KIND_PHONE
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Поделиться» есть у всего, кроме набора (#820, решение владельца 12.08.2026).
 *
 * Живой прогон: «для телефона так и не появилось функции поделиться — не могу отправить в
 * гетконтакт». Отправка значения текстом была написана ещё в #584; не хватало самого
 * действия: мерка спрашивала «есть ли файл», а у найденного номера файла нет.
 */
class ShareReachesExtractedValuesTest {

    private val share = ShareCapability()

    @Test
    fun `у найденного телефона есть чем поделиться`() {
        assertTrue(share.accepts(ObjectState(KIND_PHONE)))
    }

    @Test
    fun `дело шире одного номера — все извлечённые виды`() {
        EXTRACTED_KINDS.forEach { kind ->
            assertTrue(kind.name, share.accepts(ObjectState(kind)))
        }
    }

    @Test
    fun `файловые виды делятся как раньше`() {
        assertTrue(share.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(share.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue(share.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `у набора своё действие — второго рядом не появляется`() {
        assertFalse(share.accepts(ObjectState(ObjectKind.COLLECTION)))
        assertTrue(ShareAllCapability().accepts(ObjectState(ObjectKind.COLLECTION)))
    }
}
