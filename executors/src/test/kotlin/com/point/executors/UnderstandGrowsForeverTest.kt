package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.FallbackLlmClient
import com.point.core.flow.GraphState
import com.point.core.flow.InvestigationState
import com.point.core.flow.LlmClient
import com.point.core.flow.withInvestigation
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Понять» может бесконечно обогащать граф (#1010, решение владельца дословно).
 *
 * После успешного витка действие зовётся дальше — «Понять сильнее», — и следующий заход
 * старается взять другую модель: результат улучшается обычным merge, а не повторяется той
 * же самой.
 */
class UnderstandGrowsForeverTest {

    private val ready = object : AiReadiness {
        override fun keySet(): Boolean = true
    }

    private fun graph(vararg meta: Pair<String, String>) = GraphState(
        PointObject("obj", "text/plain", ScratchRef("/tmp/obj"), ObjectState(ObjectKind.TEXT), meta.toMap()),
    )

    @Test fun `после успеха действие зовётся «Понять сильнее»`() {
        val cap = UnderstandCapability(ready)
        val answered = withInvestigation(emptyMap(), UnderstandCapability.ID, InvestigationState.FOUND)

        val before = cap.label(graph())
        val after = cap.label(graph(*answered.toList().toTypedArray()))

        // Обещание — не точная строка, а отношение: имя меняется и зовёт «сильнее».
        assertTrue("имя не изменилось после успеха", before != after)
        assertTrue("виток не зовёт сильнее: $after", after.contains("сильнее"))
    }

    private fun provider(id: String, log: MutableList<String>) = object : LlmClient {
        override val configured = true
        override val serviceId = id
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            log += id
            val f = java.io.File.createTempFile("ans-", ".txt").apply { writeText("ответ"); deleteOnExit() }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun chain(vararg providers: LlmClient) = FallbackLlmClient(
        providers.toList(),
        facts = object : com.point.core.flow.AiFacts {
            override fun all(): Map<String, com.point.core.flow.AiFact> = emptyMap()
            override fun remember(providerId: String, outcome: com.point.core.flow.AiOutcome) = Unit
        },
        network = { true },
    )

    @Test fun `виток сильнее идёт другой моделью, когда есть кем заменить`() = runTest {
        val log = mutableListOf<String>()
        chain(provider("first", log), provider("second", log)).run(obj(), "вопрос", setOf("first"))

        assertEquals(listOf("second"), log)
    }

    @Test fun `когда заменить некем — повтор той же моделью лучше отказа`() = runTest {
        val log = mutableListOf<String>()
        chain(provider("first", log)).run(obj(), "вопрос", setOf("first"))

        assertTrue(log.isNotEmpty())
    }

    private fun obj() = PointObject("o", "text/plain", ScratchRef("/tmp/o"), ObjectState(ObjectKind.TEXT))
}
