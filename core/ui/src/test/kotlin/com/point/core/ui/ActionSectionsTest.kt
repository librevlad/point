package com.point.core.ui

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSectionsTest {

    private fun bubble(title: String, intent: Intent) =
        Bubble("i", title, CapabilityId(title), ObjectState(ObjectKind.TEXT), intent = intent)

    @Test
    fun `each intent maps to its section`() {
        assertEquals(ActionGroup.EXTRACT, actionGroupOf(Intent.UNDERSTAND))
        assertEquals(ActionGroup.TRANSFORM, actionGroupOf(Intent.PREPARE))
        assertEquals(ActionGroup.SEND, actionGroupOf(Intent.OPEN))
        assertEquals(ActionGroup.SEND, actionGroupOf(Intent.SEND))
    }

    @Test
    fun `groups bubbles into ordered sections and drops empty groups`() {
        val bubbles = listOf(
            bubble("share", Intent.SEND),
            bubble("ocr", Intent.UNDERSTAND),
            bubble("pdf", Intent.PREPARE),
            bubble("ai", Intent.UNDERSTAND),
        )
        val sections = actionSections(bubbles)

        assertEquals(
            listOf(ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.SEND),
            sections.map { it.group },
        )

        assertEquals(listOf("ocr", "ai"), sections.first().bubbles.map { it.title })
    }

    @Test
    fun `a single group yields exactly one section`() {
        val sections = actionSections(listOf(bubble("ocr", Intent.UNDERSTAND)))
        assertEquals(1, sections.size)
        assertEquals(ActionGroup.EXTRACT, sections.single().group)
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(actionSections(emptyList()).isEmpty())
    }

    @Test
    fun `короткий раздел показывается целиком`() {

        (0..LIKELY_COUNT + 2).forEach { total ->
            assertEquals("раздел из $total свернулся зря", total, likelyCount(total))
        }
    }

    @Test
    fun `длинный раздел сворачивается до верхних`() {

        assertEquals(LIKELY_COUNT, likelyCount(LIKELY_COUNT + 3))
        assertEquals(LIKELY_COUNT, likelyCount(12))
    }

    @Test
    fun `видно всегда меньше или столько же, сколько есть`() {

        (0..30).forEach { total -> assertTrue(likelyCount(total) in 0..total) }
    }
}
