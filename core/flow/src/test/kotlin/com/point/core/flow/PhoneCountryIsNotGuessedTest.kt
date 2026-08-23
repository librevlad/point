package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * Тот же номер, прочитанный дважды — с кодом и без, — остаётся одним знанием везде.
     *
     * Тождество считалось по E.164, а код страны для записи без `+` брался у первой
     * подошедшей страны. На украинском телефоне `918-682-1551` становился немецким, а
     * `+1 918-682-1551` рядом — американским: человек видел спор «или:» на месте одного
     * номера. На американском телефоне тот же чек давал одну строку.
     */
    @Test
    fun `один номер в двух записях не становится спором ни на одном устройстве`() {
        listOf("UA", "US", "DE", "PL").forEach { device ->
            val merged = mergeFacts(
                mapOf(META_ENTITY_PHONE to "+1 918-682-1551"),
                mapOf(META_ENTITY_PHONE to fromOklahoma),
                device,
            )

            assertNull(
                "на устройстве-$device один номер показан спором-${merged[META_ENTITY_PHONE + META_ALT_SUFFIX]}",
                merged[META_ENTITY_PHONE + META_ALT_SUFFIX],
            )
        }
    }

    @Test
    fun `тождество номера не спрашивает, где стоит устройство`() {
        val pairs = listOf(
            "+1 918-682-1551" to fromOklahoma,
            "067 636 05 60" to "+380676360560",
            "+380676360560" to "+380501112233",
            fromOklahoma to "(918) 682-1551",
        )

        pairs.forEach { (left, right) ->
            val answers = listOf("UA", "US", "DE", "PL")
                .map { sameFact(META_ENTITY_PHONE, left, right, it) }

            assertEquals(
                "«одно ли это знание» зависит от устройства-$left-$right-$answers",
                1,
                answers.distinct().size,
            )
        }
    }

    /**
     * Вид номера — тоже свойство страны, и по первой подошедшей он угадывался так же, как
     * формат: «городской» на украинском телефоне (немецкий городской) и ничего на
     * американском.
     */
    @Test
    fun `вид номера не угадывается по стране устройства`() {
        assertNull("вид назван по угаданной стране", PhoneNumbers.kind(fromOklahoma, "UA"))
        assertEquals(
            "вид номера зависит от устройства",
            PhoneNumbers.kind(fromOklahoma, "US"),
            PhoneNumbers.kind(fromOklahoma, "UA"),
        )
    }

    /**
     * Известную страну устройство вправе не повторять (#1129), но подменить её не может:
     * на любом устройстве это тот же украинский номер, а не чужой.
     */
    @Test
    fun `устройство не меняет страну известного номера`() {
        val ukrainian = "+380676360560"

        listOf("UA", "US", "DE", "PL").forEach { device ->
            val shown = PhoneNumbers.shown(ukrainian, device)

            assertEquals(
                "страна номера изменилась на устройстве-$device",
                "UA",
                PhoneNumbers.country(ukrainian, device),
            )
            assertTrue(
                "на устройстве-$device дописан чужой код-$shown",
                !shown.contains("+") || shown.startsWith("+380"),
            )
            assertTrue(
                "на устройстве-$device показан другой номер-$shown",
                PhoneNumbers.same(shown, ukrainian, device),
            )
        }
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
