package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чужое сообщение на экран не выходит (#686, #992).
 *
 * На свежем устройстве «Убрать фон» отвечало вендорским «Waiting for the subject segmentation
 * optional module to be downloaded. Please wait.» — по-английски, со значком отказа и без
 * единого указания, что делать.
 */
class OurWordsForVendorTest {

    private val mine = "Не удалось отделить объект от фона — попробуйте ещё раз"

    @Test
    fun `английское сообщение вендора наружу не выходит`() {
        val vendor = "Waiting for the subject segmentation optional module to be downloaded. Please wait."

        val said = ourWordsFor(vendor, mine)

        assertNotEquals("человеку показано чужое сообщение", vendor, said)
        assertFalse("наружу вышли чужие слова: $said", said.contains("module"))
        assertTrue("сказано не по-русски: $said", said.any { it in 'а'..'я' })
        assertEquals(NOT_READY_YET, said)
    }

    @Test
    fun `у ожидания есть инструкция, а не просто «не вышло»`() {
        assertTrue("не сказано, что делать", NOT_READY_YET.contains("Попробуйте"))
        assertFalse("наружу вышел механизм", NOT_READY_YET.contains("модул", ignoreCase = true))
    }

    @Test
    fun `чужое сообщение о другом заменяется своими словами действия`() {
        assertEquals(mine, ourWordsFor("java.lang.IllegalStateException: internal error 7", mine))
        assertEquals(mine, ourWordsFor(null, mine))
        assertEquals(mine, ourWordsFor("   ", mine))
    }

    @Test
    fun `свои слова проходят как есть`() {
        val ours = "Объект на фото не найден"

        assertEquals(ours, ourWordsFor(ours, mine))
    }
}
