package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Действие, которому нужен текст, не опережает то, которое этот текст добывает (#996).
 *
 * На PDF, из которого не прочитано ни строчки, главным и подсвеченным стояло «Перевести» —
 * переводить нечего, — а «Извлечь текст», шаг, открывающий всё остальное, стоял вторым и без
 * подсветки. На DOCX продукт делал это правильно; значит дело не в умении, а в старшинстве.
 */
class TextComesBeforeWhatNeedsItTest {

    private val llm = object : LlmClient {
        override val configured = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("не нужен")
    }

    private val usage = object : CapabilityUsage {
        override fun counts(): Map<CapabilityId, Int> = emptyMap()
        override suspend fun record(id: CapabilityId) = Unit
    }

    private val policy = LearningBubblePolicy(usage, llm)

    /**
     * Тот, кто добывает текст. Приоритет у него нарочно хуже: без правила он и оказывался
     * вторым — ровно как «Извлечь текст» под «Перевести» на живом PDF.
     */
    private val givesText = object : Capability {
        override val id = CapabilityId("extract-text")
        override val icon = ""
        override val meta = CapabilityMeta(priority = 60)
        override fun label(state: ObjectState) = "Извлечь текст"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
        override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)
    }

    private val needsText = TranslateCapability(AiReadiness { true })

    private fun order(state: ObjectState) = policy
        .rank(
            GraphState(PointObject("doc", "application/pdf", ScratchRef("/tmp/schet.pdf"), state)),
            listOf(needsText, givesText),
        )
        .map { it.id.value }

    @Test
    fun `на непрочитанном документе первым идёт тот, кто даст текст`() {
        val order = order(ObjectState(ObjectKind.PDF))

        assertEquals(givesText.id.value, order.first())
        assertTrue("действие пропало из списка", needsText.id.value in order)
    }

    @Test
    fun `когда текст уже прочитан, старшинство прежнее`() {
        val order = order(ObjectState(ObjectKind.PDF, setOf(Feature.HAS_TEXT)))

        assertEquals(needsText.id.value, order.first())
    }
}
