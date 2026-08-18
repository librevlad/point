package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один вопрос — одно знание, сколько бы наблюдений его ни принесло (ADR-0001 §15).
 *
 * Три случая, которые Point обязан различать, и путались все три:
 *
 * - прочтения одного факта — одно знание, разногласие остаётся видно;
 * - разные сущности того же вида — два знания, спора между ними нет;
 * - тот же факт от второго исполнителя — одно значение и два пути к нему.
 */
class OneFactManyObservationsTest {

    private val address = META_ENTITY_PREFIX + "address"
    private val phone = META_ENTITY_PHONE
    private val date = META_ENTITY_PREFIX + "date"

    @Test
    fun `адрес с прилипшим соседним словом — то же место, а не второе`() {
        assertTrue(
            sameFact(
                address,
                "М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39.",
                "ЕВГЕНІИВНА М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39",
            ),
        )
    }

    @Test
    fun `два разных адреса остаются двумя знаниями`() {
        assertFalse(sameFact(address, "вул. Кодацька, 39", "вул. Сонячна, 15"))
    }

    @Test
    fun `один день с временем и без — одна дата`() {
        assertTrue(sameFact(date, "16.04.2026", "16.04.2026 09:02:50"))
    }

    @Test
    fun `разные дни спорят, а не сливаются`() {
        assertFalse(sameFact(date, "16.04.2026", "18.04.2026 09:02:50"))
    }

    @Test
    fun `номер в другой записи — тот же номер`() {
        assertTrue(sameFact(phone, "067 636 05 60", "+380676360560", region = "UA"))
    }

    @Test
    fun `цифры внутри других цифр — не то же знание`() {
        assertFalse(sameFact(META_ENTITY_PREFIX + "track", "20451491549395", "4514915493"))
    }

    @Test
    fun `второе прочтение того же адреса спорит, а не становится вторым местом`() {
        val known = mapOf(address to "М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39.")
        val merged = mergeKnowledge(known, mapOf(address to "ЕВГЕНІИВНА М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39"))

        val kept = listOf(merged.getValue(address)) + alternativesOf(merged, address)
        assertEquals("оба прочтения одного адреса — одно знание-$kept", 1, kept.size)
    }

    @Test
    fun `одно значение от двух исполнителей — один факт и два пути`() {
        val first = mapOf(phone to "+380676360560", phone + META_ACTOR_SUFFIX to "tesseract")
        val merged = mergeKnowledge(
            first,
            mapOf(phone to "+380676360560", phone + META_ACTOR_SUFFIX to "mistral-ocr"),
        )

        assertEquals("+380676360560", merged[phone])
        assertEquals(emptyList<String>(), alternativesOf(merged, phone))
        assertEquals(listOf("tesseract", "mistral-ocr"), actorsOf(merged, phone))
    }

    @Test
    fun `разные значения от двух исполнителей остаются спором с обоими именами`() {
        val first = mapOf(date to "16.04.2026", date + META_ACTOR_SUFFIX to "tesseract")
        val merged = mergeKnowledge(
            first,
            mapOf(date to "18.04.2026", date + META_ACTOR_SUFFIX to "openrouter"),
        )

        val kept = listOf(merged.getValue(date)) + alternativesOf(merged, date)
        assertTrue("16.04 потеряно-$kept", kept.any { it.contains("16.04") })
        assertTrue("18.04 потеряно-$kept", kept.any { it.contains("18.04") })
        assertEquals(listOf("tesseract", "openrouter"), actorsOf(merged, date))
    }

    @Test
    fun `имя исполнителя знанием не становится`() {
        val merged = mergeKnowledge(
            emptyMap(),
            mapOf(phone to "+380676360560", phone + META_ACTOR_SUFFIX to "tesseract"),
        )

        assertTrue(isAnnotationKey(phone + META_ACTOR_SUFFIX))
        assertEquals(
            "исполнитель не должен становиться строкой знания",
            listOf(phone),
            knowledgeRows(merged).map { it.key },
        )
        assertEquals(
            InvestigationState.FOUND,
            investigationOutcome(merged, listOf(phone, phone + META_ACTOR_SUFFIX)),
        )
    }

    @Test
    fun `один исполнитель не подписывает чужое знание`() {
        val known = mapOf(phone to "+380676360560", phone + META_ACTOR_SUFFIX to "tesseract")

        // «Понять» возвращает вместе со своей находкой всё известное знание объекта.
        val told = known + mapOf(date to "16.04.2026")
        val stamped = findingsBy(known, told, "openrouter")

        assertEquals(listOf("tesseract"), actorsOf(stamped, phone))
        assertEquals(listOf("openrouter"), actorsOf(stamped, date))
    }
}
