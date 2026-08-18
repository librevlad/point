package com.point.core.flow

import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Знание переживает переезд между устройствами (ADR-0001 §20, #811).
 *
 * «На той стороне это тот же объект, а не новый. Потеря уже полученного знания при переносе —
 * дефект.» У устройств разные способности, и это нормально; ненормально, когда чужое
 * «я такого не умею» стирает уже полученный ответ.
 */
class KnowledgeSurvivesTheTripTest {

    private val question = CapabilityId("entities")

    // Значения — данные, а не строки экрана: тождество знания от языка не зависит.
    private val address = "Kodatska 39, Pavlograd"
    private val amount = "7800 UAH"
    private val phoneNode = "node-on-the-phone"

    @Test
    fun `чужое «не нашлось» не отменяет находку`() {
        val known = withInvestigation(
            mapOf(META_ENTITY_ADDRESS to address),
            question,
            InvestigationState.FOUND,
        )

        val back = mergeKnowledge(known, withInvestigation(emptyMap(), question, InvestigationState.NOT_FOUND))

        assertEquals(InvestigationState.FOUND, investigationStateOf(back, question))
        assertEquals(address, back[META_ENTITY_ADDRESS])
    }

    @Test
    fun `«не смотрели» тоже не отменяет находку`() {
        val known = withInvestigation(emptyMap(), question, InvestigationState.FOUND)

        val back = mergeKnowledge(known, withInvestigation(emptyMap(), question, InvestigationState.NOT_INVESTIGATED))

        assertEquals(InvestigationState.FOUND, investigationStateOf(back, question))
    }

    @Test
    fun `после честного просмотра «не нашлось» остаётся ответом`() {
        val back = mergeKnowledge(
            emptyMap(),
            withInvestigation(emptyMap(), question, InvestigationState.NOT_FOUND),
        )

        assertEquals(InvestigationState.NOT_FOUND, investigationStateOf(back, question))
    }

    @Test
    fun `знание с той стороны прирастает к тому же объекту`() {
        val known = mapOf(META_ENTITY_ADDRESS to address)
        val fromPc = mapOf(META_ENTITY_PREFIX + "amount" to amount, META_ORIGIN_ID to phoneNode)

        val merged = mergeKnowledge(known, fromPc)

        assertEquals(address, merged[META_ENTITY_ADDRESS])
        assertEquals(amount, merged[META_ENTITY_PREFIX + "amount"])
        assertEquals(phoneNode, merged[META_ORIGIN_ID])
    }
}
