package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «или:» показывает расхождение, а не победителя ещё раз (#1011).
 *
 * Кандидатов было два: `918-682-1561` — верный, совпал с бумагой, и `918-682-1551` — ошибка
 * местного чтения. Выбран верный, и это правильно. Но в строку «или:» попадал первый элемент
 * списка, а он равен выбранному: человек читал «или: 918-682-1561» под «(918) 682-1561», а
 * настоящая альтернатива, ради которой строка и существует, до него не доходила.
 */
class DisputeShowsDisagreementTest {

    private val phone = META_ENTITY_PHONE

    @Test
    fun `победитель в расхождение не попадает`() {
        val known = mapOf(
            phone to "918-682-1561",
            phone + META_ALT_SUFFIX to "918-682-1561\n918-682-1551",
        )

        assertEquals(listOf("918-682-1551"), disputedValues(known, phone))
    }

    /** Один номер, записанный по-разному, — одно знание (#932), а не спор. */
    @Test
    fun `иначе записанный тот же номер расхождением не считается`() {
        val known = mapOf(
            phone to "+380676360560",
            phone + META_ALT_SUFFIX to "067 636 05 60",
        )

        assertTrue("спор из одного и того же номера", disputedValues(known, phone).isEmpty())
    }

    @Test
    fun `день, записанный по-разному, спором не становится`() {
        val key = META_ENTITY_PREFIX + "date"
        val known = mapOf(key to "03.01.2026", key + META_ALT_SUFFIX to "3 січня 2026\n05.01.2026")

        assertEquals(listOf("05.01.2026"), disputedValues(known, key))
    }

    @Test
    fun `настоящее расхождение остаётся видно строкой знания`() {
        val known = mapOf(
            phone to "918-682-1561",
            phone + META_ALT_SUFFIX to "918-682-1561\n918-682-1551",
        )

        val row = knowledgeRows(known).single { it.key == phone }

        assertEquals(listOf("918-682-1551"), row.disputed)
        assertFalse("спор потерян вовсе", row.disputed.isEmpty())
    }
}
