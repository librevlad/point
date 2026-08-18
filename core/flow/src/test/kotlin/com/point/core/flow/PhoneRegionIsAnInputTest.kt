package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Страна для разбора номеров — вход, а не состояние мира (#1129).
 *
 * Раньше подсказка лежала изменяемой переменной: тот же текст разбирался по-разному в
 * зависимости от того, что записали в неё раньше, и по графу этого видно не было.
 */
class PhoneRegionIsAnInputTest {

    @Test
    fun `подсказка страны меняет разбор предсказуемо`() {
        val number = "+380676360560"

        assertTrue("украинский номер должен существовать в UA", PhoneNumbers.exists(number, "UA"))
        assertNotEquals(
            "дома номер показывается по-домашнему, из-за границы — международно",
            PhoneNumbers.human(number, "UA"),
            PhoneNumbers.human(number, "PL"),
        )
    }

    @Test
    fun `порядок вызовов не меняет результат`() {
        val number = "+380676360560"

        val first = PhoneNumbers.human(number, "UA")
        PhoneNumbers.human(number, "US")
        PhoneNumbers.human(number, "PL")
        val again = PhoneNumbers.human(number, "UA")

        assertEquals("предыдущий разбор не смеет влиять на следующий", first, again)
    }

    @Test
    fun `подсказка по умолчанию неизменна`() {
        assertEquals(PhoneNumbers.DEFAULT_REGION, DEFAULT_PHONE_REGION.code())
        assertEquals(PhoneNumbers.DEFAULT_REGION, DEFAULT_PHONE_REGION.code())
    }

    @Test
    fun `своя страна у номера не называется, чужая называется`() {
        val own = shownKnowledge(META_ENTITY_PHONE, "+380676360560", emptyMap(), region = "UA")
        val abroad = shownKnowledge(META_ENTITY_PHONE, "+380676360560", emptyMap(), region = "PL")

        assertTrue("своя страна названа лишний раз-$own", !own.contains("Украина"))
        assertTrue("чужая страна не названа-$abroad", abroad.contains("Украина"))
    }
}
