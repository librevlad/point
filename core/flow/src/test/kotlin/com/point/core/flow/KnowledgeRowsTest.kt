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

    @Test
    fun `открытые вопросы - «смотрели, не нашли» отличимо от «не смотрели»`() {
        val questions = openQuestions(
            mapOf(
                "investigated.qr-content" to "not_found",
                "investigated.image-text" to "found",
                "investigated.pc-understand" to "contradictory",
                "investigated.ocr@r10,10,20,20" to "not_found",
            ),
        ) { id -> mapOf("qr-content" to "QR-код", "pc-understand" to "Понимание")[id.value] }

        assertEquals(
            mapOf("QR-код" to InvestigationState.NOT_FOUND, "Понимание" to InvestigationState.CONTRADICTORY),
            questions.associate { it.name to it.state },
        )
    }

    /**
     * Человек сам обвёл область и ждёт ответа про неё сильнее всего (#1000): раньше ответ был
     * только в графе, и экран после фокуса выглядел ровно так же, как до него.
     */
    @Test
    fun `ответ про показанную область доходит до человека`() {
        val graph = mapOf(
            "investigated.entities" to "not_found",
            "investigated.entities@10 10 20 20" to "not_found",
        )
        val name = { id: com.point.core.model.CapabilityId ->
            if (id.value == "entities") "Значения" else null
        }

        val общий = openQuestions(graph, nameOf = name)
        val подФокусом = openQuestions(graph, scope = "10 10 20 20", nameOf = name)

        assertEquals(listOf(false), общий.map { it.aboutArea })
        assertEquals("ответ про область не вытеснил общий", listOf(true), подФокусом.map { it.aboutArea })
    }

    @Test
    fun `вопрос без человеческого имени не показывается — сырой id не выходит на экран`() {
        val questions = openQuestions(
            mapOf("investigated.some-internal" to "not_found"),
        ) { null }

        assertTrue(questions.isEmpty())
    }
}
