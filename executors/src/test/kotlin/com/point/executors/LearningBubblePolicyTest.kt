package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityUsage
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningBubblePolicyTest {

    private val state = ObjectState(ObjectKind.TEXT)

    private fun cap(id: String, priority: Int, auth: Boolean = false) = object : Capability {
        override val id = CapabilityId(id)
        override val icon = id
        override val meta = CapabilityMeta(priority = priority, auth = auth)
        override fun label(state: ObjectState) = id
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private fun llm(configured: Boolean) = object : com.point.core.flow.LlmClient {
        override val configured = configured
        override suspend fun run(
            obj: com.point.core.model.PointObject,
            prompt: String,
        ): com.point.core.model.ResultObject = error("политика модель не зовёт")
    }

    private fun usage(counts: Map<String, Int>) = object : CapabilityUsage {
        override fun counts() = counts.mapKeys { CapabilityId(it.key) }
        override suspend fun record(id: CapabilityId) = Unit
    }

    private fun pins(pinnedId: String? = null) = object : com.point.core.flow.PinnedActions {
        override fun pinnedFor(kind: ObjectKind) = pinnedId?.let { CapabilityId(it) }
        override suspend fun pin(kind: ObjectKind, id: CapabilityId) = Unit
        override suspend fun unpin(kind: ObjectKind) = Unit
    }

    private fun order(policy: LearningBubblePolicy, caps: List<Capability>) =
        policy.rank(state, caps).map { it.id.value }

    @Test
    fun `a pinned action ranks first — before any usage or priority (#66 user rule)`() {
        val policy = LearningBubblePolicy(pins("save"), usage(mapOf("a" to 25)), llm(true))
        val caps = listOf(cap("b", 50), cap("a", 50), cap("save", 70))
        assertEquals(listOf("save", "a", "b"), order(policy, caps))
    }

    @Test
    fun `with no usage it is priority then id — the default order`() {
        val policy = LearningBubblePolicy(pins(), usage(emptyMap()), llm(true))
        val caps = listOf(cap("b", 50), cap("a", 50), cap("save", 70))
        assertEquals(listOf("a", "b", "save"), order(policy, caps))
    }

    @Test
    fun `a frequently-used capability rises above a same-priority peer`() {
        val policy = LearningBubblePolicy(pins(), usage(mapOf("b" to 10)), llm(true))
        val caps = listOf(cap("a", 50), cap("b", 50))
        assertEquals(listOf("b", "a"), order(policy, caps))
    }

    @Test
    fun `без ключа действие, которому нужен ключ, не встаёт главным`() {
        val policy = LearningBubblePolicy(pins(), usage(emptyMap()), llm(configured = false))

        val caps = listOf(cap("understand", 31, auth = true), cap("ocr", 50))
        assertEquals(listOf("ocr", "understand"), order(policy, caps))
    }

    @Test
    fun `с ключом порядок прежний — приоритет решает`() {
        val policy = LearningBubblePolicy(pins(), usage(emptyMap()), llm(configured = true))
        val caps = listOf(cap("understand", 31, auth = true), cap("ocr", 50))
        assertEquals(listOf("understand", "ocr"), order(policy, caps))
    }

    @Test
    fun `закреплённое человеком остаётся первым даже без ключа`() {

        val policy = LearningBubblePolicy(pins("understand"), usage(emptyMap()), llm(configured = false))
        val caps = listOf(cap("understand", 31, auth = true), cap("ocr", 50))
        assertEquals(listOf("understand", "ocr"), order(policy, caps))
    }

    @Test
    fun `heavy usage can outrank a higher-priority capability`() {
        val policy = LearningBubblePolicy(pins(), usage(mapOf("save" to 30)), llm(true))
        val caps = listOf(cap("transform", 50), cap("save", 70))

        assertEquals(listOf("save", "transform"), order(policy, caps))
    }
}
