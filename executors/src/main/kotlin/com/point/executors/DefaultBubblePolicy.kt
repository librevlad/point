package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.model.ObjectState
import javax.inject.Inject

/**
 * Deterministic default: sort by (meta.priority, id) — id breaks ties so order
 * never depends on DI set iteration. Swap this for an ML/LLM policy later without
 * touching the registry or UI.
 */
class DefaultBubblePolicy @Inject constructor() : BubblePolicy {
    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> =
        candidates.sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
}
