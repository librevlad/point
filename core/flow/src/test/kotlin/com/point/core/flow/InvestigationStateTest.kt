package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
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
}
