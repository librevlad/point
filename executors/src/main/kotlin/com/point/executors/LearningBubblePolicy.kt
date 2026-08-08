package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.PinnedActions
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import javax.inject.Inject
import kotlin.math.min

class LearningBubblePolicy @Inject constructor(
    private val pins: PinnedActions,
    private val usage: CapabilityUsage,
    private val llm: com.point.core.flow.LlmClient,
) : BubblePolicy {

    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> =
        order(state, candidates, intent = null)

    /**
     * Тот же порядок, но с Intent из состояния: уместный сейчас смысл поднимается выше,
     * оставаясь ранжированием, а не фильтром (Конституция §8, ADR-0001 §14).
     */
    override fun rank(
        graph: com.point.core.flow.GraphState,
        candidates: List<Capability>,
    ): List<Capability> = order(graph.state, candidates, graph.intent)

    private fun order(
        state: ObjectState,
        candidates: List<Capability>,
        intent: com.point.core.model.Intent?,
    ): List<Capability> {
        val counts = usage.counts()

        val pinned = runCatching { pins.pinnedFor(state.kind) }.getOrNull()
        val keyless = !runCatching { llm.configured }.getOrDefault(true)
        return candidates.sortedWith(
            compareBy(
                { if (it.id == pinned) 0 else 1 },

                { if (keyless && it.meta.auth) 1 else 0 },

                { if (intent == null || intent in it.intents(state)) 0 else 1 },
                { effectivePriority(it, counts) },
                { it.id.value },
            ),
        )
    }

    private fun effectivePriority(c: Capability, counts: Map<CapabilityId, Int>): Int =
        c.meta.priority - min(counts[c.id] ?: 0, MAX_BOOST)

    private companion object {

        const val MAX_BOOST = 25
    }
}
