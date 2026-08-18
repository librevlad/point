package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сказанное о знании совпадает с самим знанием (#1052, #988, #1011).
 *
 * Три места, где Point отчитывался об одном, а показывал другое: исправление, которое не
 * становилось главным; вопрос, закрытый «найдено» на негодном файле; строка «или:», в которой
 * стояло то же самое значение.
 */
class KnowledgeSaysWhatItDidTest {

    private val phone = META_ENTITY_PREFIX + "phone"

    @Test fun `исправление становится главным, а прежнее уходит в «или»`() {
        val known = mapOf(phone to "918-682-1551", phone + META_SOURCE_SUFFIX to "ocr")

        val fixed = applyFixes(known, mapOf(phone to "918-682-1561"))
        val merged = mergeKnowledge(known, fixed)

        assertEquals("на экране осталось прежнее неверное значение", "918-682-1561", merged[phone])
        assertTrue(
            "прежнее прочтение потеряно",
            alternativesOf(merged, phone).any { it.contains("1551") },
        )
    }

    @Test fun `подтверждённое человеком исправление модели не вытесняет`() {
        val known = mapOf(phone to "918-682-1551", phone + META_SOURCE_SUFFIX to Provenance.HUMAN.wire)

        val merged = mergeKnowledge(known, applyFixes(known, mapOf(phone to "918-682-1561")))

        assertEquals("слово человека вытеснено машиной", "918-682-1551", merged[phone])
    }

    @Test fun `негодный файл оставляет вопрос открытым, а не «найдено»`() {
        val told = listOf(META_UNUSABLE_REASON)
        val state = investigationOutcome(mapOf(META_UNUSABLE_REASON to "Файл не открылся"), told)

        assertNotEquals("вопрос закрыт находкой на файле, который не открылся", InvestigationState.FOUND, state)
        assertEquals(InvestigationState.INSUFFICIENTLY_INVESTIGATED, state)
    }

    @Test fun `пустое чтение по-прежнему честно закрывается как не найдено`() {
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(emptyMap(), listOf(META_READING_MODE)),
        )
    }

}
