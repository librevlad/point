package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон судит библиотека, а не самодельное «10–13 цифр» (#801).
 *
 * Прежняя мерка жила в двух копиях — у ответа модели и у офлайн-движка — и не умела главного:
 * отличить существующий номер от набора цифр нужной длины. Номер карты и трек-номер проходили
 * её насквозь, а три записи одного номера считались тремя разными знаниями.
 */
class PhoneIsJudgedByLibraryTest {

    @Test
    fun `настоящий номер узнаётся в любой записи`() {
        listOf("+380676360560", "0676360560", "067 636 05 60", "(067) 636-05-60").forEach {
            assertTrue("не узнан номер: $it", PhoneNumbers.exists(it, "UA"))
        }
    }

    @Test
    fun `набор цифр нужной длины телефоном не становится`() {
        listOf("1234567890123", "0000000000", "5375411234567890").forEach {
            assertTrue("выдуман телефон из: $it", !PhoneNumbers.exists(it, "UA"))
        }
    }

    @Test
    fun `три записи одного номера — одно знание`() {
        assertTrue(PhoneNumbers.same("067 636 05 60", "+380676360560", "UA"))
        assertTrue(PhoneNumbers.same("0676360560", "+38 (067) 636-05-60", "UA"))
        assertTrue("разные номера не должны сливаться",
            !PhoneNumbers.same("+380676360560", "+380501112233", "UA"))
    }

    @Test
    fun `хранится единообразно, показывается по-человечески`() {
        assertEquals("+380676360560", PhoneNumbers.e164("067 636 05 60", "UA"))

        val shown = PhoneNumbers.human("+380676360560", "UA")
        assertNotNull(shown)
        assertTrue("человеку показывают слипшиеся цифры: $shown", shown!!.contains(" "))
    }

    @Test
    fun `у номера видно страну и вид`() {
        assertEquals("UA", PhoneNumbers.country("+380676360560", "UA"))

        val kind = PhoneNumbers.kind("+380676360560", "UA")
        assertNotNull("вид номера не назван", kind)
        assertTrue("мобильный не узнан: $kind", kind!!.contains("мобиль"))
    }

    @Test
    fun `имя рядом с номером номером не делает`() {
        val glued = "Тарасенко Світлана Сергіївна 067 636 05 60"

        assertTrue("склейка с именем встала телефоном", !PhoneNumbers.exists(glued, "UA"))
    }

    @Test
    fun `чужая страна не выдаётся за свою`() {
        assertEquals("PL", PhoneNumbers.country("+48221234567", "UA"))
    }
}
