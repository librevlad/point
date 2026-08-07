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

    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> {
        val counts = usage.counts()

        val pinned = runCatching { pins.pinnedFor(state.kind) }.getOrNull()
        val keyless = !runCatching { llm.configured }.getOrDefault(true)
        return candidates.sortedWith(
            compareBy(
                { if (it.id == pinned) 0 else 1 },

                { if (keyless && it.meta.auth) 1 else 0 },
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
