package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.core.model.keepShownOrder
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Понятое не предлагается понять заново (#1010).
 *
 * После «✓ Стало понятнее» главным подсвеченным действием оставалось «Понять» — с той же
 * подписью «найдёт суть, суммы, даты и контакты». Экран предлагал как лучший следующий шаг
 * ровно то, что человек только что сделал.
 */
class UnderstoodDoesNotAskToUnderstandTest {

    private val ready = AiReadiness { true }

    private val llm = object : LlmClient {
        override val configured = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("не нужен")
    }

    private val usage = object : com.point.core.flow.CapabilityUsage {
        override fun counts(): Map<CapabilityId, Int> = emptyMap()
        override suspend fun record(id: CapabilityId) = Unit
    }

    private val policy = LearningBubblePolicy(usage, llm)

    private class Other(id: String, priority: Int) : Capability {
        override val id = CapabilityId(id)
        override val icon = ""
        override val meta = CapabilityMeta(priority = priority)
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
        override fun intents(state: ObjectState) = setOf(Intent.PREPARE)
    }

    private val understand = UnderstandCapability(ready)

    private val other = Other("other", priority = 90)

    private fun photo(metadata: Map<String, String>) = PointObject(
        "img", "image/jpeg", ScratchRef("/tmp/шот.jpg"), ObjectState(ObjectKind.IMAGE), metadata,
    )

    private fun order(metadata: Map<String, String>) =
        policy.rank(GraphState(photo(metadata)), listOf(understand, other)).map { it.id.value }

    @Test
    fun `до понимания «Понять» стоит по приоритету`() {
        assertEquals(listOf(understand.id.value, other.id.value), order(emptyMap()))
    }

    @Test
    fun `после понимания оно уступает место`() {
        val understood = order(mapOf(META_SEMANTIC_SUMMARY to "чек магазина на напитки"))

        assertEquals(other.id.value, understood.first())
        assertTrue("действие пропало из списка", understand.id.value in understood)
    }

    /**
     * Правило «порядок не переставляется под пальцем» защищает того, кто целится, — и к тому,
     * что человек уже нажал, не относится.
     */
    @Test
    fun `выполненное действие ранжируется заново, остальные остаются на местах`() {
        val shown = listOf(bubble("understand"), bubble("other"), bubble("third"))
        val fresh = listOf(bubble("other"), bubble("third"), bubble("understand"))

        val pinned = keepShownOrder(shown, fresh).map { it.capabilityId.value }
        val moved = keepShownOrder(shown, fresh, justDone = setOf(CapabilityId("understand")))
            .map { it.capabilityId.value }

        assertEquals(listOf("understand", "other", "third"), pinned)
        assertEquals(listOf("other", "third", "understand"), moved)
    }

    private fun bubble(id: String) = Bubble(
        icon = "",
        title = id,
        capabilityId = CapabilityId(id),
        expectedNextState = ObjectState(ObjectKind.IMAGE),
        tier = BubbleTier.SMART,
        intent = Intent.UNDERSTAND,
    )
}
