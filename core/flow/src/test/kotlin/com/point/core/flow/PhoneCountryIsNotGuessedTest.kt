package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Страна номера не додумывается показом (#1029).
 *
 * Чек из Оклахомы: в графе лежал честный `918-682-1551` и никакой страны, а на экране стояло
 * «+49 9186 821551». Правило «страна — только когда она одна» соблюдал разбор знания, а показ
 * шёл мимо и брал первую подошедшую страну из списка подсказок. Один и тот же чек читался
 * немецким на телефоне владельца и американским на эмуляторе — по стране устройства, а не по
 * документу.
 *
 * Решение владельца 21.08.2026: не додумывать страну. Номер без кода страны печатается как в
 * документе; код дописывается только когда страна известна из самого документа.
 */
class PhoneCountryIsNotGuessedTest {

    /** Номер с чека FAMILY DOLLAR, Muskogee, OK: годится и Америке, и Германии. */
    private val fromOklahoma = "918-682-1551"

    @Test
    fun `номер без кода страны показывается как в документе`() {
        assertEquals(fromOklahoma, PhoneNumbers.shown(fromOklahoma, "UA"))
    }

    @Test
    fun `один документ читается на всех устройствах одинаково`() {
        val everywhere = listOf("UA", "US", "DE", "PL").map { PhoneNumbers.shown(fromOklahoma, it) }

        assertEquals("страна показа зависит от устройства-$everywhere", 1, everywhere.distinct().size)
    }

    @Test
    fun `страна не приписывается номеру, у которого её нет`() {
        val guessed = listOf("+49", "+1", "+380", "+48")

        guessed.forEach {
            assertTrue(
                "номеру дописан код страны-$it",
                !PhoneNumbers.shown(fromOklahoma, "UA").contains(it),
            )
        }
    }

    @Test
    fun `названный в документе код остаётся на экране`() {
        assertTrue(PhoneNumbers.shown("+1 918-682-1551", "UA").startsWith("+1"))
        assertTrue(PhoneNumbers.shown("+380676360560", "PL").startsWith("+380"))
    }

    /** Известную страну устройство по-прежнему вправе не называть: свой код человек знает. */
    @Test
    fun `свой номер остаётся домашним`() {
        assertEquals("067 636 0560", PhoneNumbers.shown("+380676360560", "UA"))
    }

    @Test
    fun `строка знания не приписывает номеру чужую страну`() {
        val row = shownKnowledge(META_ENTITY_PHONE, fromOklahoma, emptyMap(), region = "UA")

        assertEquals(fromOklahoma, row)
    }

    /** Показ и знание отвечают одно и то же: страны, которой нет в графе, нет и на экране. */
    @Test
    fun `показ и знание об одной стране согласны`() {
        listOf(fromOklahoma, "+1 918-682-1551", "067 123 45 67", "+380676360560").forEach { text ->
            val known = PhoneNumbers.country(text, "UA") != null
            val shownAsRead = PhoneNumbers.shown(text, "UA") == text

            assertTrue(
                "экран знает про страну больше графа-$text",
                known || shownAsRead,
            )
        }
    }
}
