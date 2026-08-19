package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие независимых исполнителей — улика (#1176): без слоя слов единственное
 * прочтение и знание, увиденное двумя моделями, выглядели одинаково. Спорное
 * согласием не считается — спор виден спором (P8).
 */
class AgreementEvidenceTest {

    private val key = META_ENTITY_PREFIX + "meter"

    @Test fun `двое увидели одно — по отметке на свидетеля`() {
        val evidence = agreementEvidence(
            mapOf(key to "20842", key + META_ACTOR_SUFFIX to "gemini,groq"),
            listOf(key),
        )

        val marks = evidence.getValue(key + META_EVIDENCE_SUFFIX)
        assertTrue(marks.contains(AGREE_MARK + "gemini"))
        assertTrue(marks.contains(AGREE_MARK + "groq"))
    }

    @Test fun `один свидетель — согласия нет`() {
        assertEquals(
            emptyMap<String, String>(),
            agreementEvidence(mapOf(key to "20842", key + META_ACTOR_SUFFIX to "gemini"), listOf(key)),
        )
    }

    @Test fun `спорное согласием не подделывается`() {
        assertEquals(
            emptyMap<String, String>(),
            agreementEvidence(
                mapOf(
                    key to "20842",
                    key + META_ACTOR_SUFFIX to "gemini,groq",
                    key + META_ALT_SUFFIX to "20843",
                ),
                listOf(key),
            ),
        )
    }

    @Test fun `прежняя улика остаётся рядом, отметки не копятся вслепую`() {
        val metadata = mapOf(
            key to "20842",
            key + META_ACTOR_SUFFIX to "gemini,groq",
            key + META_EVIDENCE_SUFFIX to "semantic," + AGREE_MARK + "gemini",
        )

        val marks = agreementEvidence(metadata, listOf(key)).getValue(key + META_EVIDENCE_SUFFIX)

        assertTrue(marks.startsWith("semantic"))
        assertEquals("отметка задвоилась: $marks", 1, marks.split(',').count { it == AGREE_MARK + "gemini" })
    }
}
