package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единая семантика merge — ADR-0001 §15, RFC §19.
 *
 * Раньше знание приходило двумя путями с разными правилами: `mergeFacts` хранил расхождение,
 * а путь обогащения выбрасывал новое значение, если ключ уже был занят.
 */
class MergeKnowledgeTest {

    private val phone = "entity.phone"

    @Test
    fun `two sources reading the same value agree without alternatives`() {
        val known = mapOf(phone to "+380671234567")
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234567"))

        assertEquals("+380671234567", merged[phone])
        assertEquals(emptyList<String>(), alternativesOf(merged, phone))
    }

    @Test
    fun `two sources reading different values keep both as a conflict`() {
        val known = mapOf(phone to "+380671234567")
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234599"))

        val kept = alternativesOf(merged, phone) + merged.getValue(phone)
        assertTrue("+380671234567 потерян-$kept", kept.contains("+380671234567"))
        assertTrue("+380671234599 потерян-$kept", kept.contains("+380671234599"))
    }

    @Test
    fun `a second reading is never dropped just because the key is already taken`() {
        val known = mapOf("entity.track" to "20 4514 9154 9395")
        val merged = mergeKnowledge(known, mapOf("entity.track" to "20 4514 9154 9999"))

        assertTrue(
            "новое прочтение исчезло молча",
            merged.getValue("entity.track") == "20 4514 9154 9999" ||
                alternativesOf(merged, "entity.track").contains("20 4514 9154 9999"),
        )
    }

    @Test
    fun `provenance climbs to the stronger source and never falls back`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire)
        val merged = mergeKnowledge(known, mapOf(phone + META_SOURCE_SUFFIX to Provenance.HUMAN.wire))

        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))

        val back = mergeKnowledge(merged, mapOf(phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))
        assertEquals(Provenance.HUMAN, provenanceOf(back, phone))
    }

    @Test
    fun `evidence is kept and only replaced by a better grounded one`() {
        val known = mapOf(phone to "+380671234567", phone + META_EVIDENCE_SUFFIX to "semantic,lexical")
        val merged = mergeKnowledge(known, mapOf(phone + META_EVIDENCE_SUFFIX to "semantic"))
        assertEquals("semantic,lexical", merged[phone + META_EVIDENCE_SUFFIX])

        val richer = mergeKnowledge(merged, mapOf(phone + META_EVIDENCE_SUFFIX to "semantic,lexical,structural"))
        assertEquals("semantic,lexical,structural", richer[phone + META_EVIDENCE_SUFFIX])
    }

    @Test
    fun `alternatives from both sides are united, not replaced`() {
        val known = mapOf(phone to "A", phone + META_ALT_SUFFIX to altValue(listOf("A", "B")))
        val merged = mergeKnowledge(known, mapOf(phone + META_ALT_SUFFIX to altValue(listOf("C"))))

        assertEquals(listOf("A", "B", "C"), alternativesOf(merged, phone))
    }

    @Test
    fun `repeating a known reading does not erase the stored conflict`() {
        val known = mapOf(phone to "A", phone + META_ALT_SUFFIX to altValue(listOf("A", "B")))
        val merged = mergeKnowledge(known, mapOf(phone to "A"))

        assertEquals(listOf("A", "B"), alternativesOf(merged, phone))
    }

    @Test
    fun `refreshable references are replaced, not reconciled`() {
        val known = mapOf(META_OCR_TEXT_REF to "/scratch/old.txt")
        val merged = mergeKnowledge(
            known,
            mapOf(META_OCR_TEXT_REF to "/scratch/new.txt"),
            refreshable = setOf(META_OCR_TEXT_REF),
        )

        assertEquals("/scratch/new.txt", merged[META_OCR_TEXT_REF])
        assertEquals(emptyList<String>(), alternativesOf(merged, META_OCR_TEXT_REF))
    }

    @Test
    fun `investigation state is a state, so the fresh one wins instead of becoming a conflict`() {
        val qr = CapabilityId("qr")
        val known = withInvestigation(emptyMap(), qr, InvestigationState.NOT_FOUND)
        val merged = mergeKnowledge(known, withInvestigation(emptyMap(), qr, InvestigationState.FOUND))

        assertEquals(InvestigationState.FOUND, investigationStateOf(merged, qr))
        assertEquals(emptyList<String>(), alternativesOf(merged, investigationKey(qr)))
    }

    // ---- Этап 5: человек как источник знания (ADR §8, RFC §19) ----

    private fun human(key: String, value: String) =
        mapOf(key to value, key + META_SOURCE_SUFFIX to Provenance.HUMAN.wire)

    @Test
    fun `human correction becomes primary and keeps the machine reading as history`() {
        val known = mapOf(phone to "111", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, human(phone, "112"))

        assertEquals("112", merged[phone])
        assertEquals(listOf("111"), alternativesOf(merged, phone))
        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))
        assertTrue("исправление человеком — не спор", !isDisputed(merged, phone))
        assertTrue(!isDoubtful(merged.filterKeys { it.startsWith(phone) }))
    }

    @Test
    fun `human value survives later model ocr and rule readings`() {
        var m = mergeKnowledge(mapOf(phone to "111"), human(phone, "112"))

        m = mergeKnowledge(m, mapOf(phone to "113", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))
        m = mergeKnowledge(m, mapOf(phone to "114", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire))
        m = mergeKnowledge(m, mapOf(phone to "115", phone + META_SOURCE_SUFFIX to Provenance.RULE.wire))

        assertEquals("человеческое значение не вытесняется", "112", m[phone])
        assertEquals(Provenance.HUMAN, provenanceOf(m, phone))
        assertTrue("машинные чтения не пропадают", alternativesOf(m, phone).containsAll(listOf("111")))
    }

    @Test
    fun `a machine repair-shaped reading cannot overwrite the human value`() {

        val known = mergeKnowledge(mapOf(phone to "вул. Сонячна 15"), human(phone, "вул. Сонячна 15б"))
        val m = mergeKnowledge(known, mapOf(phone to "вул. Сонячна 156"))

        assertEquals("вул. Сонячна 15б", m[phone])
        assertEquals(Provenance.HUMAN, provenanceOf(m, phone))
    }

    @Test
    fun `human confirmation keeps the value and adds no artificial conflict`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, human(phone, "+380671234567"))

        assertEquals("+380671234567", merged[phone])
        assertEquals(emptyList<String>(), alternativesOf(merged, phone))
        assertEquals(Provenance.HUMAN, provenanceOf(merged, phone))
        assertTrue(!isDoubtful(merged.filterKeys { it.startsWith(phone) }))
    }

    @Test
    fun `machine versus machine conflict semantics are untouched by the human rule`() {
        val known = mapOf(phone to "+380671234567", phone + META_SOURCE_SUFFIX to Provenance.OCR.wire)
        val merged = mergeKnowledge(known, mapOf(phone to "+380671234599", phone + META_SOURCE_SUFFIX to Provenance.MODEL.wire))

        val kept = alternativesOf(merged, phone) + merged.getValue(phone)
        assertTrue(kept.containsAll(listOf("+380671234567", "+380671234599")))
        assertTrue("машинный спор остаётся спором", isDisputed(merged, phone))
    }

    @Test
    fun `a newer human word replaces the older human word, keeping it in history`() {
        val first = mergeKnowledge(emptyMap(), human(phone, "112"))
        val second = mergeKnowledge(first, human(phone, "119"))

        assertEquals("119", second[phone])
        assertTrue(alternativesOf(second, phone).contains("112"))
        assertEquals(Provenance.HUMAN, provenanceOf(second, phone))
    }
}
