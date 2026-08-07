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
    fun `показанное остаётся на своих местах, новое дописывается следом`() {
        val shown = listOf(bubble("ocr"), bubble("read-harder"), bubble("ai"))

        val fresh = listOf(bubble("understand"), bubble("ai"), bubble("ocr"), bubble("read-harder"))

        assertEquals(listOf("ocr", "read-harder", "ai", "understand"), order(keepShownOrder(shown, fresh)))
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
        assertEquals(listOf("ocr", "understand", "excel"), order(keepShownOrder(shown, fresh)))
    }
}
