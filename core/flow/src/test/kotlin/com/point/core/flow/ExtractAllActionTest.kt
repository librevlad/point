package com.point.core.flow

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
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "a@b.com"),
                Entity(EntityType.URL, "https://x.com"),
                Entity(EntityType.MONEY, "$5"),
            ),
        )
        assertTrue(out.contains("Телефоны:"))
        assertTrue(out.contains("Почты:"))
        assertTrue(out.contains("Ссылки:"))
        assertEquals(1, Regex("""\+380671234567""").findAll(out).count())
        assertFalse(out.contains("$5"))
    }

    @Test
    fun `empty when nothing actionable`() {
        assertEquals("", formatEntities(listOf(Entity(EntityType.MONEY, "$5"))))
    }

    @Test
    fun `собирать предлагается там, где есть что собирать`() {

        // #676 (охота 2026-08-09): на узле «Адрес» действие возвращало копию
        // самого себя новым объектом — пустая работа при формальном успехе.
        val cap = ExtractAllCapability()

        assertTrue(
            "два вида — есть что сводить",
            cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE, Feature.HAS_EMAIL))),
        )
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PHONE, Feature.HAS_ADDRESS))))

        assertFalse("одно значение собирать не из чего", cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(
            "узел знания — не источник сводки",
            cap.accepts(ObjectState(com.point.core.flow.KIND_ADDRESS, setOf(Feature.HAS_ADDRESS, Feature.HAS_PHONE))),
        )
    }
}
