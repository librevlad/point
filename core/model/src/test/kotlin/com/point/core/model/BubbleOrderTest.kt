package com.point.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleOrderTest {

    private fun bubble(id: String) = Bubble(
        icon = id,
        title = id,
        capabilityId = CapabilityId(id),
        expectedNextState = ObjectState(ObjectKind.TEXT),
    )

    private fun order(list: List<Bubble>) = list.map { it.capabilityId.value }

    @Test
    fun `показанное не переставляется, а новое встаёт по ранжированию`() {
        val shown = listOf(bubble("ocr"), bubble("read-harder"), bubble("ai"))

        val fresh = listOf(bubble("understand"), bubble("ai"), bubble("ocr"), bubble("read-harder"))

        assertEquals(listOf("ocr", "read-harder", "understand", "ai"), order(keepShownOrder(shown, fresh)))
    }

    /**
     * #937: «Открыть ссылку» появляется вместе с найденной ссылкой — то есть позже всех, — и
     * дописывалось в конец, ниже предложения сделать из ссылки таблицу Excel.
     */
    @Test
    fun `знание поднимает своё действие, а не дописывает его в конец`() {
        val shown = listOf(bubble("understand"), bubble("excel"), bubble("open"))

        val fresh = listOf(bubble("open-url"), bubble("understand"), bubble("excel"), bubble("open"))

        assertEquals(listOf("open-url", "understand", "excel", "open"), order(keepShownOrder(shown, fresh)))
    }

    @Test
    fun `на первом кадре порядок задаёт ранжирование — держать нечего`() {
        val fresh = listOf(bubble("understand"), bubble("ocr"))
        assertEquals(listOf("understand", "ocr"), order(keepShownOrder(emptyList(), fresh)))
    }

    @Test
    fun `исчезнувшее действие не воскресает`() {
        val shown = listOf(bubble("ocr"), bubble("unzip"))
        val fresh = listOf(bubble("ocr"))
        assertEquals(listOf("ocr"), order(keepShownOrder(shown, fresh)))
    }

    @Test
    fun `порядок новых между собой остаётся тем, который дало ранжирование`() {
        val shown = listOf(bubble("ocr"))
        val fresh = listOf(bubble("understand"), bubble("ocr"), bubble("excel"))
        assertEquals(listOf("understand", "ocr", "excel"), order(keepShownOrder(shown, fresh)))
    }

    @Test
    fun `показанные друг друга не обгоняют — двигаться под пальцем нечему`() {
        val shown = listOf(bubble("excel"), bubble("understand"), bubble("open"))

        // Ранжирование поменяло местами всё показанное — и это не повод переставлять экран.
        val fresh = listOf(bubble("open"), bubble("understand"), bubble("excel"))

        assertEquals(listOf("excel", "understand", "open"), order(keepShownOrder(shown, fresh)))
    }
}
