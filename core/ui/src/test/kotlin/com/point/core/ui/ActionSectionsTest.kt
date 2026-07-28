package com.point.core.ui

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The object screen groups actions by user intent into ordered sections — pure, JVM-tested. */
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
        // Извлечь → Превратить → Отправить, regardless of input order; no empty group appears.
        assertEquals(
            listOf(ActionGroup.EXTRACT, ActionGroup.TRANSFORM, ActionGroup.SEND),
            sections.map { it.group },
        )
        // Within a group the BubblePolicy rank order is preserved.
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
}
