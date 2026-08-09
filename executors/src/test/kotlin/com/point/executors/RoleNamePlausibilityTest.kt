package com.point.executors

import com.point.core.flow.LayoutElement
import com.point.core.flow.plausiblePersonName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #654, живой прогон 2026-08-09: модель ответила ролью на целый текст («Отправитель
 * +38067…, получатель +38050…. Оплата 2500 грн…»), и Point родил «человека» из всего
 * документа. Роль без правдоподобного имени — не человек: не пишется и узла не рождает.
 */
class RoleNamePlausibilityTest {

    private val wholeText =
        "Отправитель +380671234567, получатель +380509876543. Оплата 2500 грн до 15.08.2026, офис: Киев"

    @Test
    fun `роль с целым текстом вместо имени отбрасывается`() {
        val elements = listOf(LayoutElement("e1", wholeText))

        val (roles, disputes) = roleReadings("sender=e1", elements, layer = null)

        assertTrue("мусорный человек не должен родиться: $roles", roles.isEmpty())
        assertTrue(disputes.isEmpty())
    }

    @Test
    fun `роль с человеческим именем остаётся`() {
        val elements = listOf(LayoutElement("e1", "Іваненко Іван Петрович"))

        val (roles, _) = roleReadings("sender=e1", elements, layer = null)

        assertEquals("Іваненко Іван Петрович", roles["graph.role.sender"])
    }

    @Test
    fun `голое время из ответа модели не становится датой`() {
        // #651: «11:09» из чата становилось «Нашёл дату».
        val parsed = com.point.core.flow.parseFieldCandidates("DATE=11:09\nDATE=15.08.2026")

        val dates = parsed.fields["entity.date"]!!.map { it.text }
        org.junit.Assert.assertEquals(listOf("15.08.2026"), dates)
    }

    @Test
    fun `правдоподобие имени — без цифр, коротко, со словами`() {
        assertTrue(plausiblePersonName("Іваненко Іван"))
        assertTrue(plausiblePersonName("ДУМБРОВАН Олександр Миколайович"))
        assertTrue("OCR-искажение с одиночной цифрой — ещё имя", plausiblePersonName("1ваненко ван"))
        assertTrue(!plausiblePersonName(wholeText))
        assertTrue(!plausiblePersonName("+380671234567"))
        assertTrue(!plausiblePersonName("Оплата 2500 грн"))
        assertTrue(!plausiblePersonName(""))
        assertTrue(!plausiblePersonName("и это очень длинная строка из очень многих слов подряд без шанса быть именем"))
    }
}
