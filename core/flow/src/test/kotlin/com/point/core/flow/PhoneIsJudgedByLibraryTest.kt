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
        listOf("3", "12", "1234567890123", "0000000000", "5375411234567890").forEach {
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
    fun `показывается по-человечески`() {
        val shown = PhoneNumbers.human("+380676360560", "UA")
        assertNotNull(shown)
        assertTrue("человеку показывают слипшиеся цифры: $shown", shown!!.contains(" "))
    }

    @Test
    fun `у номера видно страну и вид`() {
        assertEquals("UA", PhoneNumbers.country("+380676360560"))

        val kind = PhoneNumbers.kind("+380676360560")
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
        assertEquals("PL", PhoneNumbers.country("+48221234567"))
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

    /** Страну называет документ, а не устройство (#1029): написан код — названа страна. */
    @Test
    fun `страна называется, когда её написал документ`() {
        assertEquals("UA", PhoneNumbers.country("+380 67 636 05 60"))
    }

    @Test
    fun `американский номер существует и на украинском телефоне`() {
        assertTrue(PhoneNumbers.exists("918-682-1551", "UA"))
    }

    /**
     * Номер без кода страны о своей стране не говорит: `918-682-1551` настоящий и в Америке,
     * и в Германии. Прочитать такую запись можно только подсказкой устройства, а выдумывать
     * по ней страну нельзя (#1029): номер есть, страна неизвестна.
     */
    @Test
    fun `страна не называется, когда её не написал документ`() {
        assertNull(PhoneNumbers.country("918-682-1551"))
        assertEquals("US", PhoneNumbers.country("+1 918-682-1551"))
    }

    /** Что номером не является, номером не становится ни в одной стране. */
    @Test
    fun `код и индекс не превращаются в номер ни в одной стране`() {
        listOf("32490244", "04128", "49000").forEach {
            assertFalse("«$it» сочли номером", PhoneNumbers.exists(it, "US"))
        }
    }
}
