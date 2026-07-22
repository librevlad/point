package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityUsage
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import javax.inject.Inject
import kotlin.math.min

/**
 * Learnable ranking: like the deterministic default (sort by meta.priority, then id)
 * but each capability's *effective* priority is lowered by how often the user has
 * applied it (capped), so frequently-used bubbles drift forward — even past a tier.
 * With no usage yet it is byte-for-byte the default order, so behaviour degrades
 * gracefully. The training signal is the flow journal via [CapabilityUsage]; swapping
 * in an ML/LLM policy later touches neither the registry nor the UI.
 */
class LearningBubblePolicy @Inject constructor(
    private val usage: CapabilityUsage,
) : BubblePolicy {

    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> {
        val counts = usage.counts()
        return candidates.sortedWith(
            compareBy({ effectivePriority(it, counts) }, { it.id.value }),
        )
    }

    private fun effectivePriority(c: Capability, counts: Map<CapabilityId, Int>): Int =
        c.meta.priority - min(counts[c.id] ?: 0, MAX_BOOST)

    private companion object {
        /** Cap on how far usage may pull a capability forward — keeps priority meaningful. */
        const val MAX_BOOST = 25
    }
}
