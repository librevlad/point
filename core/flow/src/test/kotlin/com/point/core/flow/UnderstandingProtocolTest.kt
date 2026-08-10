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
    fun `склейка имени и номера одной строкой PHONE — пара, а не длинный номер`() {

        // Живой прогон 2026-08-09: модель ответила «PHONE=НОВІК Владислав
        // Анатолійович +380 93 242 37 59» — узел телефона показывал склейку целиком.
        val parsed = parseFieldCandidates("PHONE=НОВІК Владислав Анатолійович +380 93 242 37 59")

        assertEquals(
            listOf(PersonContact("НОВІК Владислав Анатолійович", "+380 93 242 37 59")),
            parsed.contacts,
        )
        val phone = parsed.fields[META_ENTITY_PREFIX + "phone"]!!.single()
        assertEquals("+380 93 242 37 59", phone.text)
        assertEquals("НОВІК Владислав Анатолійович", phone.person)
    }

    @Test
    fun `склейка не дублирует уже названный чистый номер`() {
        val parsed = parseFieldCandidates(
            "PHONE=+380 93 242 37 59\nPHONE=НОВІК Владислав +380 93 242 37 59",
        )

        val phones = parsed.fields[META_ENTITY_PREFIX + "phone"]!!
        assertEquals(1, phones.size)
        assertEquals("НОВІК Владислав", phones.single().person)
    }

    @Test
    fun `CONTACT с метками слов — имя чистое, пара живёт`() {

        // Журнал обменов 2026-08-09: модель дала все три пары с хвостом меток
        // «…| АНДРІЯЩЕНКО Артур [w47 w48 w59 w60]» — метки браковали имя.
        val parsed = parseFieldCandidates("CONTACT=+380 66 526 2706 | Іваненко Іван [w47 w48 w59]")

        assertEquals(listOf(PersonContact("Іваненко Іван", "+380 66 526 2706")), parsed.contacts)
        val phone = parsed.fields[META_ENTITY_PREFIX + "phone"]!!.single()
        assertEquals(listOf("w47", "w48", "w59"), phone.ids)
    }

    @Test
    fun `чистый номер без имени пары не рождает`() {
        val parsed = parseFieldCandidates("PHONE=+380671234567")

        assertEquals(emptyList<PersonContact>(), parsed.contacts)
        assertEquals(null, parsed.fields[META_ENTITY_PREFIX + "phone"]!!.single().person)
    }

    @Test
    fun `SUMMARY приходит без хвоста меток слов`() {

        // Живой прогон 2026-08-09: «Контактные данные… [w38 w39 w40 w41 w42]» ушло
        // подзаголовком объекта на экран.
        val parsed = parseFieldCandidates("SUMMARY=Контактные данные службы [w38 w39 w40]")

        assertEquals("Контактные данные службы", parsed.single[META_SEMANTIC_SUMMARY])
    }

    @Test
    fun `модель назвала неуверенно прочитанное — протокол это доносит (#670)`() {
        // Охота 2026-08-09: пятая цифра на барабане счётчика читалась спорно, а знание
        // приходило без единой оговорки. Сомнение модели — часть ответа, а не шум.
        val parsed = parseFieldCandidates("METER=20842\nAMOUNT=500\nUNSURE=METER")

        assertEquals(setOf(META_ENTITY_METER), parsed.unsure)
    }

    @Test
    fun `несколько неуверенных перечисляются через запятую, чужие имена молчат (#670)`() {
        val parsed = parseFieldCandidates("METER=1\nAMOUNT=2\nUNSURE=METER, AMOUNT, ЧУШЬ")

        assertEquals(setOf(META_ENTITY_METER, META_ENTITY_AMOUNT), parsed.unsure)
    }

    @Test
    fun `без строки UNSURE сомнений нет — молчание не означает «возможно» (#670)`() {
        assertEquals(emptySet<String>(), parseFieldCandidates("METER=20842").unsure)
    }

    @Test
    fun `multi-value виды названы, одиночные — нет`() {
        assertEquals(true, isMultiValueFact(META_ENTITY_PREFIX + "phone"))
        assertEquals(true, isMultiValueFact(META_ENTITY_PREFIX + "date"))
        assertEquals(false, isMultiValueFact(META_ENTITY_AMOUNT))
        assertEquals(false, isMultiValueFact(META_ENTITY_TRACK))
    }
}
