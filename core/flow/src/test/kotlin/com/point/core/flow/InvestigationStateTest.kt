package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0001 §9 — состояние знания для пары `(ObjectId, CapabilityId)`.
 *
 * «Не искали» и «искали, не нашли» обязаны быть различимы, а сорвавшееся исследование
 * не имеет права стать `NOT_FOUND`.
 */
class InvestigationStateTest {

    private val qr = CapabilityId("qr")
    private val ocr = CapabilityId("ocr")

    @Test
    fun `an untouched question reads as not investigated`() {
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(emptyMap(), qr))
    }

    @Test
    fun `state belongs to the pair, so one question says nothing about another`() {
        val metadata = withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND)

        assertEquals(InvestigationState.NOT_FOUND, investigationStateOf(metadata, qr))
        assertEquals(InvestigationState.NOT_INVESTIGATED, investigationStateOf(metadata, ocr))
    }

    @Test
    fun `a finished investigation with nothing to show is not found`() {
        assertEquals(InvestigationState.NOT_FOUND, investigationOutcome(emptyMap(), emptyList()))
    }

    /**
     * След работы — не находка (#1067).
     *
     * На тёмном снимке чтение не дало ни буквы, но оставило пометку, каким шрифтом читали, —
     * и вопрос закрывался как «найдено» при пустых руках. Человеку показывать нечего, а
     * повторно спросить Point уже не даёт.
     */
    @Test
    fun `пометка о ходе чтения не делает пустое чтение находкой`() {
        val after = mapOf(META_READING_MODE to "HANDWRITTEN")

        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(after, listOf(META_READING_MODE)),
        )
    }

    @Test
    fun `прочитанный текст — находка, даже если сущностей в нём не нашлось`() {
        val after = mapOf(META_OCR_TEXT_REF to "/scratch/text.txt", META_READING_MODE to "PRINTED")

        assertEquals(
            InvestigationState.FOUND,
            investigationOutcome(after, listOf(META_OCR_TEXT_REF, META_READING_MODE)),
        )
    }

    @Test
    fun `a finished investigation with a grounded value is found`() {
        val after = mapOf("entity.qr" to "https-//example.org")

        assertEquals(InvestigationState.FOUND, investigationOutcome(after, listOf("entity.qr")))
    }

    @Test
    fun `a value that disagrees with a stored alternative is contradictory`() {
        val after = mapOf(
            "entity.phone" to "+380671234567",
            "entity.phone" + META_ALT_SUFFIX to altValue(listOf("+380671234567", "+380671234599")),
        )

        assertEquals(InvestigationState.CONTRADICTORY, investigationOutcome(after, listOf("entity.phone")))
    }

    @Test
    fun `a value with too little evidence is investigated insufficiently`() {
        val after = mapOf(
            "entity.track" to "AA123456789UA",
            "entity.track" + META_EVIDENCE_SUFFIX to "semantic",
        )

        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationOutcome(after, listOf("entity.track")),
        )
    }

    @Test
    fun `annotations alone are not knowledge and do not turn into found`() {
        val after = mapOf("entity.phone" + META_SOURCE_SUFFIX to "ocr")

        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(after, listOf("entity.phone" + META_SOURCE_SUFFIX)),
        )
    }

    /**
     * Ответ «в области ничего» (#1000) — только про ту область, которую спрашивали, и только
     * когда её спрашивали: `not investigated` ≠ `not found`.
     */
    private val area = Focus("obj", Box(10f, 20f, 110f, 60f))

    @Test
    fun `область, под которой не задано ни одного вопроса, ответом «ничего» не считается`() {
        assertFalse("не смотрели — не «не нашлось»", nothingFoundIn(emptyMap(), area))
        assertFalse(
            "«не нашлось» про объект — не ответ про область",
            nothingFoundIn(withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND), area),
        )
    }

    @Test
    fun `область, где на каждый вопрос не нашлось, отвечает «ничего»`() {
        val metadata = withInvestigation(
            withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND, area),
            ocr, InvestigationState.NOT_FOUND, area,
        )

        assertTrue(nothingFoundIn(metadata, area))
    }

    @Test
    fun `хоть одна находка под областью снимает ответ «ничего»`() {
        val metadata = withInvestigation(
            withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND, area),
            ocr, InvestigationState.FOUND, area,
        )

        assertFalse(nothingFoundIn(metadata, area))
    }

    @Test
    fun `посмотрели недостаточно — это не «ничего»`() {
        val metadata = withInvestigation(emptyMap(), qr, InvestigationState.INSUFFICIENTLY_INVESTIGATED, area)

        assertFalse(nothingFoundIn(metadata, area))
    }

    @Test
    fun `ответ другой области за эту не считается`() {
        val other = Focus("obj", Box(0f, 100f, 50f, 150f))

        assertFalse(nothingFoundIn(withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND, other), area))
    }
}
