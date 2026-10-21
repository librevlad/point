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
        assertTrue("сумма — тоже данные (#1422)", out.contains("Суммы:\n$5"))
    }

    @Test
    fun `empty when nothing found`() {
        assertEquals("", formatEntities(emptyList()))
    }

    @Test
    fun `сводка берёт знание графа, а не только второе чтение`() {

        // #1422 (охота 03.09.2026): дата и сумма уже лежали в графе и были показаны
        // действиями, а «Собрать данные» их не брало — своим списком из четырёх видов.
        val facts = mapOf(
            META_ENTITY_PREFIX + "phone" to "+380671234567",
            META_ENTITY_PREFIX + "phone" + META_SOURCE_SUFFIX to "model",
            META_ENTITY_PREFIX + "date" to "11.09.2026 15:00",
            META_ENTITY_PREFIX + "date" + META_MORE_SUFFIX to "12.09.2026",
            META_ENTITY_AMOUNT to "1500 UAH",
            META_ENTITY_PREFIX + "address" to "Kyiv, Khreshchatyk 22",
            "name" to "note.txt",
        )
        val out = formatEntities(listOf(Entity(EntityType.PHONE, "+380 67 123 45 67")), facts)

        assertTrue(out, out.contains("Даты:\n11.09.2026 15:00\n12.09.2026"))
        assertTrue(out, out.contains("Суммы:\n1500 UAH"))
        assertTrue(out, out.contains("Адреса:\nKyiv, Khreshchatyk 22"))
        assertEquals("тот же номер иначе записанный — одна строка", 1, Regex("""\+380""").findAll(out).count())
        assertFalse("подпись при знании — не данные", out.contains("model"))
        assertFalse(out.contains("note.txt"))
        assertTrue("телефоны первыми, как и прежде", out.startsWith("Телефоны:"))
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
