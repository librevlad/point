package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сомнение чтения доходит до значения (#1109).
 *
 * Ридер знает про каждое слово, насколько он в нём уверен, и это знание кончалось на слое:
 * цифра, прочитанная плохо, приходила к человеку такой же спокойной, как прочитанная чисто, —
 * и ложная дата стояла в списке рядом с верной без единого признака сомнения.
 *
 * Новой системы уверенности для этого не заводится: пустая улика — существующий способ
 * сказать «возможно», и им уже пользуются «Понять» и экран.
 */
class DoubtReachesTheFactTest {

    private fun atom(id: String, text: String, left: Float, confidence: Float) = Atom(
        id = id,
        text = text,
        box = Box(left, 0f, left + 40f, 10f),
        confidence = confidence,
    )

    private val date = META_ENTITY_PREFIX + "date"

    @Test
    fun `значение из плохо прочитанных слов не считается уверенным`() {
        val layer = AtomLayer(listOf(atom("1", "18.04.2026", 0f, confidence = 0.3f)))

        assertEquals(false, layer.readConfidently("18.04.2026"))
    }

    @Test
    fun `чисто прочитанное остаётся уверенным`() {
        val layer = AtomLayer(listOf(atom("1", "16.04.2026", 0f, confidence = 0.95f)))

        assertEquals(true, layer.readConfidently("16.04.2026"))
    }

    @Test
    fun `о чужом значении слой молчит, а не выдумывает сомнение`() {
        val layer = AtomLayer(listOf(atom("1", "накладна", 0f, confidence = 0.95f)))

        assertNull(layer.readConfidently("16.04.2026"))
    }

    @Test
    fun `пустая улика делает значение сомнительным, но не отменяет его`() {
        val facts = mapOf(date to "18.04.2026", date + META_EVIDENCE_SUFFIX to "")

        assertTrue("сомнение не дошло до значения", isAssumption(facts, date))
        assertEquals("значение обязано остаться значением", "18.04.2026", facts[date])
        assertEquals(
            InvestigationState.INSUFFICIENTLY_INVESTIGATED,
            investigationOutcome(facts, listOf(date)),
        )
    }

    @Test
    fun `сомнение переживает слияние и не подменяет посчитанные улики`() {
        val doubted = mapOf(date to "18.04.2026", date + META_EVIDENCE_SUFFIX to "")
        val judged = mergeKnowledge(doubted, mapOf(date + META_EVIDENCE_SUFFIX to "semantic,lexical"))

        assertFalse("подтверждённое значение осталось сомнительным", isAssumption(judged, date))

        val back = mergeKnowledge(judged, mapOf(date + META_EVIDENCE_SUFFIX to ""))
        assertFalse("пустая улика затёрла подтверждение", isAssumption(back, date))
    }

    @Test
    fun `сомнение доезжает до другого устройства`() {
        val doubted = mapOf(date to "18.04.2026", date + META_EVIDENCE_SUFFIX to "")

        val onTheOtherSide = mergeKnowledge(emptyMap(), doubted)

        assertTrue(isAssumption(onTheOtherSide, date))
    }
}
