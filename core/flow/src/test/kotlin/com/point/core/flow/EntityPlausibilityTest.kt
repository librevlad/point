package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityPlausibilityTest {

    private fun phone(v: String) = Entity(EntityType.PHONE, v)
    private fun address(v: String) = Entity(EntityType.ADDRESS, v)
    private fun date(v: String) = Entity(EntityType.DATE_TIME, v)
    private fun url(v: String) = Entity(EntityType.URL, v)

    private val chatScreen = """
        20451491549395
        TTH
        Добрый день
        Ремкомплекты отправил
        вчера.
        10:00
        Один ремкомплект стоит 320
        грн
        Если вас не затруднит,
        сбросьте разницу на карту.
        4111 1111 1111 1111
        Паринкн Виктор
        300 грн
    """.trimIndent()

    @Test
    fun `обрезок длинного числа не телефон, даже когда длина совпала с телефонной`() {
        val entities = listOf(
            phone("4111 1111 1111"),
            phone("2045149154"),
            phone("+380 67 123 45 67"),
        )

        val kept = plausibleEntities(entities, chatScreen).map { it.value }

        assertEquals(listOf("+380 67 123 45 67"), kept)
    }

    @Test
    fun `отметка времени отличается от даты по форме`() {
        assertTrue(date("11:41").isBareClock())
        assertTrue(date("18:24").isBareClock())
        assertTrue(date("9:05").isBareClock())
        assertTrue(date("09:00").isBareClock())
        assertTrue(date("7:30 PM").isBareClock())

        assertFalse(date("30.03").isBareClock())
        assertFalse(date("01.04.2026").isBareClock())
        assertFalse(date("завтра о 09:00").isBareClock())
        assertFalse(date("вт, 21 июл.").isBareClock())
        assertFalse(phone("11:41").isBareClock())
    }

    @Test
    fun `голое время суток остаётся сущностью`() {
        val entities = listOf(date("11:41"), date("30.03"))

        assertEquals(listOf("11:41", "30.03"), plausibleEntities(entities, "").map { it.value })
    }

    @Test
    fun `real phones pass, waybill fragments and over-long digit runs are rejected`() {
        assertTrue(phone("+380 67 123 45 67").isPlausible())
        assertTrue(phone("0671234567").isPlausible())
        assertTrue(phone("+7 999 123-45-67").isPlausible())
        assertFalse(phone("4507 1234").isPlausible())
        assertFalse(phone("20450712345678").isPlausible())
    }

    @Test
    fun `real addresses pass, bare abbreviations are rejected`() {
        assertTrue(address("г. Киев, ул. Крещатик 12").isPlausible())
        assertTrue(address("Москва, Тверская 7").isPlausible())
        assertFalse(address("г.").isPlausible())
        assertFalse(address("ул.").isPlausible())
    }

    /**
     * Точка в домене потерялась при распознавании — `edrive.com.ua` прочиталось как
     * `edrive com.ua`, — и знанием стал хвост `com.ua` (#1028, #989). Он не открывается,
     * ничего не значит и занимает место настоящей находки рядом с верным телефоном.
     */
    @Test
    fun `доменная зона ссылкой не становится`() {
        assertFalse(url("com.ua").isPlausible())
        assertFalse(url("co.uk").isPlausible())
        assertFalse(url("ua").isPlausible())
        assertFalse(url("http://com.ua").isPlausible())

        assertTrue(url("edrive.com.ua").isPlausible())
        assertTrue(url("https://point.leerio.app/x?y=1").isPlausible())
        assertTrue(url("www.example.com").isPlausible())
        assertTrue(url("bit.ly/2Ab").isPlausible())
    }

    @Test
    fun `other entity types are never filtered`() {
        assertTrue(Entity(EntityType.EMAIL, "a@b.c").isPlausible())
        assertTrue(Entity(EntityType.DATE_TIME, "в пятницу").isPlausible())
        assertTrue(Entity(EntityType.PAYMENT_CARD, "4111111111111111").isPlausible())
    }

    @Test
    fun `plausibleEntities keeps the good and drops the noise`() {
        val filtered = plausibleEntities(
            listOf(phone("+380671234567"), phone("4507 1234"), address("г."), address("Львов, площадь Рынок 1")),
        )
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.type == EntityType.PHONE })
        assertTrue(filtered.any { it.type == EntityType.ADDRESS })
    }
}
