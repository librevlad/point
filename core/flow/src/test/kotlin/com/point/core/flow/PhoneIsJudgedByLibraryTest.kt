package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * Страна устройства — подсказка, а не приговор (#936).
     *
     * У украинца с телефоном на английском страна оказывалась `US`, и его собственные номера
     * переставали существовать — молча, все до единого. Решение владельца 13.08.2026:
     * «Несколько стран, годится любая».
     */
    @Test
    fun `украинский номер существует и на устройстве с чужой страной`() {
        assertTrue("номер потерян из-за страны телефона", PhoneNumbers.exists("067 636 05 60", "US"))
    }

    /** Страна названа, когда телефон человека и документ из одной страны — обычный случай. */
    @Test
    fun `страна называется, когда сомнений нет`() {
        assertEquals("UA", PhoneNumbers.country("+380 67 636 05 60", "UA"))
    }

    @Test
    fun `американский номер существует и на украинском телефоне`() {
        assertTrue(PhoneNumbers.exists("918-682-1551", "UA"))
    }

    /**
     * Существование проверяется по нескольким странам — значит номер может подойти сразу
     * двум. Выдумывать одну из них нельзя: номер есть, страна неизвестна.
     */
    @Test
    fun `страна не называется, когда номер годится нескольким странам`() {
        assertNull(PhoneNumbers.country("918-682-1551", "UA"))
        assertEquals("US", PhoneNumbers.country("+1 918-682-1551", "UA"))
    }

    /** Что номером не является, номером не становится ни в одной стране. */
    @Test
    fun `код и индекс не превращаются в номер ни в одной стране`() {
        listOf("32490244", "04128", "49000").forEach {
            assertFalse("«$it» сочли номером", PhoneNumbers.exists(it, "US"))
        }
    }
}
