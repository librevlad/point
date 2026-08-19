package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Серия «буквы+цифры» становится знанием и узлом (#1066, #991).
 *
 * Госномер BH9249MT был самым уверенным атомом кадра (0.998) и не становился ничем: ни у
 * одного правила не было формы для смеси букв и цифр. Отдельного типа «госномер» не
 * заводится — это идентификатор, как накладная и квитанция.
 */
class SerialBecomesKnowledgeTest {

    @Test fun `госномер с кадра становится знанием серии`() {
        val facts = serialFacts("Привет! Смотри, какую машину взял BH9249MT UA 098-777-51-76")

        assertEquals("BH9249MT", facts[META_ENTITY_SERIAL])
        assertEquals("прочитанное называет чтение", "ocr", facts[META_ENTITY_SERIAL + META_SOURCE_SUFFIX])
    }

    @Test fun `имя файла и голые цифры серией не становятся`() {
        assertNull("машинное имя файла", serialFacts("IMG20260815 в папке")[META_ENTITY_SERIAL])
        assertNull("голые цифры — земля трека и телефона", serialFacts("0932423759 и 20451491549395")[META_ENTITY_SERIAL])
        assertNull("обычные слова", serialFacts("Забрал вчера, номер уже на ней")[META_ENTITY_SERIAL])
        assertNull("короткое", serialFacts("A12 и B7")[META_ENTITY_SERIAL])
    }

    @Test fun `вторая серия уходит в ещё, а не теряется`() {
        val facts = serialFacts("BH9249MT и AA1234BB")

        assertEquals("BH9249MT", facts[META_ENTITY_SERIAL])
        assertEquals(listOf("AA1234BB"), moreOf(facts, META_ENTITY_SERIAL))
    }

    @Test fun `серия рождает узел-идентификатор — в него можно войти`() {
        val source = com.point.core.model.PointObject(
            "img",
            "image/jpeg",
            com.point.core.model.ScratchRef("/tmp/img"),
            com.point.core.model.ObjectState(com.point.core.model.ObjectKind.IMAGE),
        )
        val (objects, relations) = entityObjects(source, serialFacts("машина BH9249MT"), creator = "t")

        assertEquals(1, objects.size)
        assertEquals(KIND_IDENTIFIER, objects.single().state.kind)
        assertTrue(relations.isNotEmpty())
    }

    @Test fun `строка знания называется человеческим словом`() {
        assertEquals("Номер", understoodName(META_ENTITY_SERIAL))
    }
}
