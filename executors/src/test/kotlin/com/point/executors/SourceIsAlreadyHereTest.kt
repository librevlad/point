package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Обратное преобразование того, что Point только что сделал (#925).
 *
 * Текст → «Озвучить» → запись, и на экране записи первым действием стояло «Расшифровать»:
 * Point предлагал платным облачным вызовом получить обратно тот самый текст, из которого
 * минуту назад эту запись и сделал. Круг замыкается — и стоит человеку ожидания, квоты и
 * результата хуже исходника.
 *
 * Решение владельца 13.08.2026: «Опустить и сказать „у вас это есть“».
 */
class SourceIsAlreadyHereTest {

    private class Makes(name: String, private val kind: ObjectKind, priority: Int) : Capability {
        override val id = CapabilityId(name)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(kind)
    }

    private val text = PointObject(
        id = "текст",
        mime = "text/plain",
        uri = ScratchRef("/scratch/договор.txt"),
        state = ObjectState(ObjectKind.TEXT),
    )

    private val record = PointObject(
        id = "запись",
        mime = "audio/wav",
        uri = ScratchRef("/scratch/договор — вслух.wav"),
        state = ObjectState(ObjectKind.AUDIO),
    )

    // «Расшифровать» на записи стоит первым по своему приоритету — с него и начинается беда.
    private val transcribe = Makes("transcribe", ObjectKind.TEXT, priority = 5)
    private val toPdf = Makes("pdf", ObjectKind.PDF, priority = 50)

    private fun bubbles(graph: GraphState) = DefaultCapabilityRegistry(
        capabilities = setOf(transcribe, toPdf),
        policy = LearningBubblePolicy(
            usage = object : com.point.core.flow.CapabilityUsage {
                override fun counts() = emptyMap<CapabilityId, Int>()
                override suspend fun record(id: CapabilityId) = Unit
            },
            llm = object : com.point.core.flow.LlmClient {
                override val configured = true
                override suspend fun run(obj: PointObject, prompt: String) = error("не зовут")
            },
        ),
    ).bubblesFor(graph)

    private val spoken = GraphState(
        obj = record,
        found = listOf(text),
        relations = listOf(Relation(record.id, RelationType.DERIVED_FROM, text.id)),
    )

    @Test fun `действие, возвращающее исходник, уходит вниз`() {
        val order = bubbles(spoken).map { it.capabilityId.value }

        assertEquals(listOf("pdf", "transcribe"), order)
    }

    @Test fun `оно не прячется — дверь остаётся в списке`() {
        assertNotNull(bubbles(spoken).firstOrNull { it.capabilityId == transcribe.id })
    }

    @Test fun `человеку сказано, что исходник у него уже есть`() {
        val back = bubbles(spoken).single { it.capabilityId == transcribe.id }

        assertEquals(com.point.core.flow.sourceIsHere(ObjectKind.TEXT), back.unusableReason)
    }

    @Test fun `объект, ниоткуда не полученный, ничего не теряет`() {
        val alone = bubbles(GraphState(obj = record))

        assertNull(alone.single { it.capabilityId == transcribe.id }.unusableReason)
        assertEquals(listOf("transcribe", "pdf"), alone.map { it.capabilityId.value })
    }
}
