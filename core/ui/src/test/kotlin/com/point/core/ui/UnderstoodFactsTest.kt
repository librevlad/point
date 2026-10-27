package com.point.core.ui

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstoodFactsTest {

    private fun obj(features: Set<Feature> = emptySet(), metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "image/png", ScratchRef("/x"), ObjectState(ObjectKind.IMAGE, features), metadata)

    @Test
    fun `orders facts stably and pairs labels with values`() {
        val facts = understoodFacts(
            obj(
                features = setOf(Feature.HAS_URL, Feature.HAS_PHONE),
                metadata = mapOf(
                    "entity.phone" to "+380671234567",
                    "entity.url" to "https://github.com/librevlad",
                ),
            ),
        )
        // Структура: два факта, каждый с лейблом и значением
        assertEquals(2, facts.size)
        assertTrue("first fact has label", facts[0].label.isNotBlank())
        assertTrue("second fact has label", facts[1].label.isNotBlank())
        assertNotEquals("labels differ", facts[0].label, facts[1].label)

        // Номер показывается по-человечески (#932): слитная запись из документа
        // разбирается библиотекой и выходит на экран разбитой на группы.
        assertEquals("067 123 4567", facts[0].value)
        assertEquals("github.com/librevlad", facts[1].value)
    }

    @Test
    fun `номер карты показывается целиком — без cvv в нём нет смысла`() {

        // Решение владельца (2026-08-09, сказано трижды): маскировка карт удалена,
        // номер — рабочее значение для сверки и копирования.
        val facts = understoodFacts(obj(setOf(Feature.HAS_CARD), mapOf("entity.card" to "5375 4141 1234 5678")))
        val label = facts.single().label
        assertTrue("label not empty", label.isNotBlank())
        assertNotEquals("label is not generic kind", "Изображение", label)

        // Значение — данные (контракт)
        assertEquals("5375 4141 1234 5678", facts.single().value)
    }

    @Test
    fun `a feature without a stored value still yields a labelled fact`() {
        val facts = understoodFacts(obj(setOf(Feature.HAS_DATE)))
        val label = facts.single().label
        assertTrue("label not empty", label.isNotBlank())
        assertNotEquals("label is not generic", "Изображение", label)
        assertNull(facts.single().value)
    }

    @Test
    fun `describes featureful states without entities`() {
        val label1 = understoodFacts(obj(setOf(Feature.IS_IMAGE_PDF))).single().label
        val label2 = understoodFacts(obj(setOf(Feature.ZIP_OF_IMAGES))).single().label

        assertTrue("label1 not empty", label1.isNotBlank())
        assertTrue("label2 not empty", label2.isNotBlank())
        assertNotEquals("labels differ for different features", label1, label2)
        assertNotEquals("not generic image label", "Изображение", label1)
        assertNotEquals("not generic image label", "Изображение", label2)
    }

    @Test
    fun `an empty state yields no facts`() {
        assertTrue(understoodFacts(obj()).isEmpty())
    }

    // ---- Этап 10, F6: слово человека видно и на родительском факте, не только на chip ----

    @Test
    fun `подтверждённый человеком факт несёт подпись — подтверждено вами`() {
        val facts = understoodFacts(
            obj(
                features = setOf(Feature.HAS_PHONE),
                metadata = mapOf(
                    "entity.phone" to "112",
                    "entity.phone" + com.point.core.flow.META_ALT_SUFFIX to "111",
                    "entity.phone" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.HUMAN.wire,
                ),
            ),
        )
        // Значение пришло из входных данных теста
        assertEquals("112", facts.single().value)
        // Подпись — текст для человека
        val note = facts.single().note
        assertTrue("note not empty", note?.isNotBlank() == true)
        assertTrue("note contains human confirmation", note?.contains("подтвержден") == true)
    }

    @Test
    fun `машинное происхождение подписью факта не становится`() {
        val facts = understoodFacts(
            obj(
                features = setOf(Feature.HAS_PHONE),
                metadata = mapOf(
                    "entity.phone" to "111",
                    "entity.phone" + com.point.core.flow.META_SOURCE_SUFFIX to
                        com.point.core.model.Provenance.OCR.wire,
                ),
            ),
        )
        assertNull("машинные подписи живут на chips, строка фактов молчит", facts.single().note)
    }
}
