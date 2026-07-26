package com.point.executors

import com.point.core.flow.Entity
import com.point.core.flow.EntityType
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractAllActionTest {

    @Test
    fun `groups deduped entities into sections`() {
        val out = formatEntities(
            listOf(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.PHONE, "+380671234567"), // duplicate
                Entity(EntityType.EMAIL, "a@b.com"),
                Entity(EntityType.URL, "https://x.com"),
                Entity(EntityType.MONEY, "$5"), // no section — ignored
            ),
        )
        assertTrue(out.contains("Телефоны:"))
        assertTrue(out.contains("Почты:"))
        assertTrue(out.contains("Ссылки:"))
        assertEquals(1, Regex("""\+380671234567""").findAll(out).count()) // deduped
        assertFalse(out.contains("$5"))
    }

    @Test
    fun `empty when nothing actionable`() {
        assertEquals("", formatEntities(listOf(Entity(EntityType.MONEY, "$5"))))
    }

    @Test
    fun `accepts any object with entities — a text or an OCR-enriched image`() {
        val cap = ExtractAllCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT))) // no entities → not offered
        // A screenshot whose OCR enrichment flagged entities collects them too (#64).
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PHONE))))
    }
}
