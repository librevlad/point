package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один слой проверки формы на все виды знания (#657).
 *
 * Прогон 2026-08-09: номер карты вставал телефоном, «квитанцію» — трек-номером, товарная
 * строка — адресом. Форму спрашивали только у даты, трека, суммы и адреса, а остальные виды
 * проходили как есть: правило было, но не для всех.
 *
 * Правила намеренно односторонние: они умеют сказать «это точно не оно» и молчат там, где
 * жизнь богаче правила. Молчание по-прежнему значит «пропустить» — гейт отсекает только явно
 * чужое.
 */
class OneFormGateForAllKnowledgeTest {

    private fun key(kind: String) = META_ENTITY_PREFIX + kind

    @Test
    fun `карта не становится телефоном`() {
        assertFalse(factFits(key("phone"), "5169 3351 0965 2632"))
        assertTrue("настоящий телефон проходит", factFits(key("phone"), "067 636 05 60"))
    }

    @Test
    fun `имя рядом с номером не делает номер значением телефона`() {
        assertFalse(factFits(key("phone"), "Тарасенко Світлана Сергіївна 067 636 05 60"))
    }

    @Test
    fun `почта без собаки почтой не становится`() {
        assertFalse(factFits(key("email"), "hello point ua"))
        assertTrue(factFits(key("email"), "hello@aroma.ua"))
    }

    @Test
    fun `место словами не выдаёт себя за координаты`() {
        assertFalse(factFits(key("geo"), "Одеська область, Бритівка"))
        assertTrue(factFits(key("geo"), "46.1953, 30.3428"))
    }

    @Test
    fun `слово не становится номером квитанции`() {
        assertFalse(factFits(key("receipt"), "квитанція"))
    }

    @Test
    fun `карта остаётся картой`() {
        assertTrue(factFits(key("card"), "5169 3351 0965 2632"))
        assertFalse("телефон картой не становится", factFits(key("card"), "067 636 05 60"))
    }

    @Test
    fun `вид знания без правила формы проходит как раньше`() {
        assertTrue("правила нет — молчим и пропускаем", factFits(key("colour"), "синий"))
        assertTrue(factFits("name", "Договір №432/69"))
    }

    @Test
    fun `пустое и отказ модели не проходят ни для какого вида`() {
        listOf("phone", "email", "geo", "card", "receipt", "meter").forEach { kind ->
            assertFalse(kind, factFits(key(kind), "   "))
            assertFalse(kind, factFits(key(kind), "Извините, я не могу определить"))
        }
    }
}
