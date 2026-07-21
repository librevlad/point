package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import javax.inject.Inject

/**
 * The Flow Graph, derived from the set of capabilities (Hilt multibinding). The
 * bubbles for a state ARE the accepting capabilities, ranked by the [BubblePolicy]
 * — no stored transition table. `produces == null` (e.g. AI) falls back to the
 * same state as an advisory edge; the real next state is re-derived after run.
 */
class DefaultCapabilityRegistry @Inject constructor(
    private val capabilities: Set<@JvmSuppressWildcards Capability>,
    private val policy: BubblePolicy,
) : CapabilityRegistry {

    private val byIdMap: Map<CapabilityId, Capability> = capabilities.associateBy { it.id }

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        policy.rank(state, capabilities.filter { it.accepts(state) })
            .map { c ->
                Bubble(
                    icon = c.icon,
                    title = c.label(state),
                    capabilityId = c.id,
                    expectedNextState = c.produces(state) ?: state,
                )
            }

    override fun byId(id: CapabilityId): Capability =
        byIdMap[id] ?: error("No capability registered for id=${id.value}")
}
