package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт вывода «уместного сейчас смысла» (Конституция §6, ADR-0001 §14):
 * Intent выводится из Graph, контекста и Focus — и только из них. Не хранится,
 * не вводится пользователем, пересчитывается при каждом изменении состояния.
 */
class LeadingIntentTest {

    private val photo = PointObject(
        "photo", "image/jpeg", ScratchRef("/scratch/p.jpg"), ObjectState(ObjectKind.IMAGE),
    )

    private fun graph(
        metadata: Map<String, String> = emptyMap(),
        focus: Focus? = null,
    ) = GraphState(photo.copy(metadata = metadata), focus = focus)

    @Test
    fun `focus makes understanding the leading intent`() {
        assertEquals(
            Intent.UNDERSTAND,
            leadingIntent(graph(focus = Focus(photo.id, region = Box(0f, 0f, 10f, 10f)))),
        )
    }

    @Test
    fun `an open question makes understanding the leading intent`() {
        val asked = withInvestigation(emptyMap(), CapabilityId("qr"), InvestigationState.NOT_FOUND)

        assertEquals(Intent.UNDERSTAND, leadingIntent(graph(metadata = asked)))
    }

    @Test
    fun `running work makes understanding the leading intent`() {
        assertEquals(Intent.UNDERSTAND, leadingIntent(graph(), working = true))
    }

    @Test
    fun `nothing to understand - no leading intent`() {
        assertNull(leadingIntent(graph()))
    }

    @Test
    fun `new knowledge closes the question and the intent goes away`() {
        val asked = withInvestigation(emptyMap(), CapabilityId("qr"), InvestigationState.NOT_FOUND)
        assertEquals(Intent.UNDERSTAND, leadingIntent(graph(metadata = asked)))

        val answered = withInvestigation(asked, CapabilityId("qr"), InvestigationState.FOUND)
        assertNull("закрытый вопрос больше не зовёт понимать", leadingIntent(graph(metadata = answered)))
    }

    @Test
    fun `a crop-shaped object carries no intent by itself`() {

        val cropLike = mapOf(
            "sel.source" to "photo",
            "sel.region" to "0.0 0.0 10.0 10.0",
        )

        assertNull(leadingIntent(graph(metadata = cropLike)))
    }

    @Test
    fun `human provenance is knowledge, not intent`() {
        val humanFact = mapOf(
            "entity.phone" to "+380671234567",
            "entity.phone" + META_SOURCE_SUFFIX to Provenance.HUMAN.wire,
        )

        assertNull(leadingIntent(graph(metadata = humanFact)))
    }
}
