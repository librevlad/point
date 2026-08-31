package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.flow.InvestigationState
import com.point.core.flow.withInvestigation
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Следующее действие следует из состояния графа, а не из вида входа (#1010, #996, #1140).
 *
 * Ranking получал вид объекта, признаки и Intent — и больше ничего. Поэтому «Понять»
 * оставалось главным после успешного «Понять», а «Перевести» стояло первым там, где читать
 * нечего.
 */
class RankingSeesKnowledgeTest {

    private val policy = LearningBubblePolicy(
        usage = object : com.point.core.flow.CapabilityUsage {
            override fun counts(): Map<CapabilityId, Int> = emptyMap()
            override suspend fun record(id: CapabilityId) = Unit
        },
        llm = object : com.point.core.flow.LlmClient {
            override val configured = true
            override suspend fun run(obj: PointObject, prompt: String) = error("не нужно")
        },
    
            com.point.core.flow.RememberingLinkMonitor(),
        )

    private class Simple(
        id: String,
        private val needs: String? = null,
        private val weight: Int = 10,
    ) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta(priority = weight)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
        override fun missing(state: ObjectState) = needs
    }

    private fun graph(vararg meta: Pair<String, String>, kind: ObjectKind = ObjectKind.IMAGE) = GraphState(
        PointObject(
            "obj",
            "image/png",
            ScratchRef("/tmp/obj"),
            ObjectState(kind, setOf(Feature.HAS_TEXT)),
            metadata = meta.toMap(),
        ),
    )

    @Test fun `отвеченный вопрос уступает место неотвеченному`() {
        val answered = Simple("understand", weight = 1)
        val other = Simple("excel", weight = 50)
        val known = withInvestigation(emptyMap(), answered.id, InvestigationState.FOUND)

        val order = policy.rank(graph(*known.toList().toTypedArray()), listOf(answered, other))

        assertEquals("успешно выполненное исследование осталось главным", other.id, order.first().id)
    }

    /**
     * Вопрос — заявленный действием, а не его id (#1119): «Считать QR» отвечает на
     * qr-content, и при уже показанном содержимом уходит вниз, не исчезая.
     */
    @Test fun `действие с чужим id вопроса уступает, когда его вопрос закрыт`() {
        val readQr = object : Capability {
            override val id = CapabilityId("read-qr")
            override val icon = "x"
            override val meta = CapabilityMeta(priority = 1, answers = CapabilityId("qr-content"))
            override fun label(state: ObjectState) = "Считать QR"
            override fun accepts(state: ObjectState) = true
            override fun produces(state: ObjectState) = state
        }
        val other = Simple("excel", weight = 50)
        val known = withInvestigation(emptyMap(), CapabilityId("qr-content"), InvestigationState.FOUND)

        val order = policy.rank(graph(*known.toList().toTypedArray()), listOf(readQr, other))

        assertEquals("действие стоит над уже показанным содержимым", other.id, order.first().id)
        assertTrue("действие пропало из списка", order.map { it.id }.contains(readQr.id))
    }

    @Test fun `неотвеченный вопрос своего места не теряет`() {
        val fresh = Simple("understand", weight = 1)
        val other = Simple("excel", weight = 50)

        val order = policy.rank(graph(), listOf(fresh, other))

        assertEquals(fresh.id, order.first().id)
    }

    @Test fun `действие, которому нечем работать, уступает тому, кто даёт ему пищу`() {
        val hungry = Simple("translate", needs = "сначала извлеките текст", weight = 1)
        val feeder = Simple("extract-text", weight = 50)

        val order = policy.rank(graph(), listOf(hungry, feeder))

        assertEquals("главным осталось то, чему нечего делать", feeder.id, order.first().id)
        assertTrue("голодное действие пропало из списка", order.map { it.id }.contains(hungry.id))
    }
}
