package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Скрин владельца 2026-08-09 (чек monobank): модель ответила «DATE=26.04.2026
 * 26.04.2026», и слипшееся значение прошло в спор целиком. Несколько дат в одном
 * значении — несколько кандидатов; одинаковые схлопываются.
 */
class UnderstandingProtocolTest {

    private fun dates(answer: String): List<String> =
        parseFieldCandidates(answer).fields[META_ENTITY_PREFIX + "date"].orEmpty().map { it.text }

    @Test
    fun `две одинаковые даты в одном значении — один кандидат`() {
        assertEquals(listOf("26.04.2026"), dates("DATE=26.04.2026 26.04.2026"))
    }

    @Test
    fun `две разные даты в одном значении — два кандидата`() {
        assertEquals(listOf("26.04.2026", "28.04.2026"), dates("DATE=26.04.2026 28.04.2026"))
    }

    @Test
    fun `дата с временем остаётся целым значением`() {
        assertEquals(listOf("26.04.2026 20:04"), dates("DATE=26.04.2026 20:04"))
    }

    @Test
    fun `резаные кандидаты не обходят общий дедуп строк ответа`() {
        assertEquals(
            listOf("26.04.2026"),
            dates("DATE=26.04.2026 26.04.2026\nDATE=26.04.2026"),
        )
    }

    // #653, решение владельца: «просто дергай контакты и по возможности связывай их
    // с именами». CONTACT=<номер> | <имя> — пара; имя-мусор пары не рождает.

    @Test
    fun `контакт с именем — пара, и номер среди кандидатов телефона`() {
        val parsed = parseFieldCandidates("CONTACT=+380 66 526 2706 | АНДРІЯЩЕНКО Артур Миколайович")

        assertEquals(
            listOf(PersonContact("АНДРІЯЩЕНКО Артур Миколайович", "+380 66 526 2706")),
            parsed.contacts,
        )
        val phone = parsed.fields[META_ENTITY_PREFIX + "phone"]!!.single()
        assertEquals("+380 66 526 2706", phone.text)
        assertEquals("АНДРІЯЩЕНКО Артур Миколайович", phone.person)
    }

    @Test
    fun `имя-мусор пары не рождает, но номер остаётся`() {
        val whole = "CONTACT=+380671234567 | Оплата 2500 грн до 15.08.2026 офис Киев набережная 12"
        val parsed = parseFieldCandidates(whole)

        assertEquals(emptyList<PersonContact>(), parsed.contacts)
        assertEquals("+380671234567", parsed.fields[META_ENTITY_PREFIX + "phone"]!!.single().text)
    }

    @Test
    fun `порядок сторон пары прощается`() {
        val parsed = parseFieldCandidates("CONTACT=НОВІК Владислав | +380 93 242 37 59")

        assertEquals(listOf(PersonContact("НОВІК Владислав", "+380 93 242 37 59")), parsed.contacts)
    }

    @Test
    fun `дубль номера из PHONE и CONTACT — один кандидат, имя не теряется`() {
        val parsed = parseFieldCandidates(
            "PHONE=+380665262706\nCONTACT=+380 66 526 2706 | ДУМБРОВАН Олександр",
        )

        val phones = parsed.fields[META_ENTITY_PREFIX + "phone"]!!
        assertEquals(1, phones.size)
        assertEquals("ДУМБРОВАН Олександр", phones.single().person)
    }

    @Test
    fun `multi-value виды названы, одиночные — нет`() {
        assertEquals(true, isMultiValueFact(META_ENTITY_PREFIX + "phone"))
        assertEquals(true, isMultiValueFact(META_ENTITY_PREFIX + "date"))
        assertEquals(false, isMultiValueFact(META_ENTITY_AMOUNT))
        assertEquals(false, isMultiValueFact(META_ENTITY_TRACK))
    }
}
