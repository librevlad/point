package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит десктопа 2026-08-09, блок 2.2: знание показывалось кастрированно — 4 факта,
 * споры и «ещё»-значения отфильтрованы. Строки знания — общая модель: всё знание,
 * спор виден, «ещё» видно, слово человека помечено (P8).
 */
class KnowledgeRowsTest {

    @Test
    fun `все факты видны — без лимита и с человеческими именами`() {
        val rows = knowledgeRows(
            mapOf(
                "entity.phone" to "+380671234567",
                "entity.email" to "a@b.c",
                "entity.date" to "26.04.2026",
                "entity.amount" to "500",
                "entity.receipt" to "№ 4411",
                "entity.geo" to "50.45, 30.52",
            ),
        )

        assertEquals(6, rows.size)
        assertEquals(
            setOf("Телефон", "Почта", "Дата", "Сумма", "Квитанция", "Координаты"),
            rows.map { it.name }.toSet(),
        )
    }

    @Test
    fun `спор и «ещё» не прячутся, слово человека помечено`() {
        val rows = knowledgeRows(
            mapOf(
                "entity.amount" to "500",
                "entity.amount.alt" to altValue(listOf("0.00", "100")),
                "entity.phone" to "+380111111111",
                "entity.phone.more" to altValue(listOf("+380222222222")),
                "entity.phone.src" to "human",
            ),
        )

        val amount = rows.first { it.key == "entity.amount" }
        assertEquals(listOf("0.00", "100"), amount.disputed)
        val phone = rows.first { it.key == "entity.phone" }
        assertEquals(listOf("+380222222222"), phone.more)
        assertTrue("слово человека обязано быть помечено", phone.confirmed)
        assertTrue(amount.disputed.isNotEmpty() && !amount.confirmed)
    }

    @Test
    fun `валюта суммы — часть значения, а не отдельная строка`() {
        val rows = knowledgeRows(
            mapOf("entity.amount" to "128500", "entity.amount.currency" to "руб."),
        )

        assertEquals(listOf("128500 руб."), rows.map { it.value })
    }

    @Test
    fun `служебные и вложенные ключи строками не становятся`() {
        val rows = knowledgeRows(
            mapOf(
                "entity.phone" to "+380671234567",
                "entity.phone.src" to "ocr",
                "entity.phone.ev" to "digits",
                "investigated.ocr" to "found",
                "name" to "чек.jpg",
                "semantic.summary" to "Оплата счёта",
            ),
        )

        assertEquals(listOf("entity.phone"), rows.map { it.key })
    }

    // Было: «смотрели, не нашли» выходило строкой на экран. Решение владельца (#1016,
    // дословно): «не нашлось не надо показывать - я не просил». Знание остаётся в графе,
    // наружу выходят только спор и «посмотрели недостаточно».
    @Test
    fun `открытые вопросы - непрошенное «не нашлось» молчит, спор виден`() {
        val questions = openQuestions(
            mapOf(
                "investigated.qr-content" to "not_found",
                "investigated.image-text" to "found",
                "investigated.pc-understand" to "contradictory",
                "investigated.ocr@r10,10,20,20" to "not_found",
            ),
        ) { id -> mapOf("qr-content" to "QR-код", "pc-understand" to "Понимание")[id.value] }

        assertEquals(
            mapOf("Понимание" to InvestigationState.CONTRADICTORY),
            questions.associate { it.name to it.state },
        )
    }

    @Test
    fun `вопрос без человеческого имени не показывается — сырой id не выходит на экран`() {
        val questions = openQuestions(
            mapOf("investigated.some-internal" to "not_found"),
        ) { null }

        assertTrue(questions.isEmpty())
    }
}
