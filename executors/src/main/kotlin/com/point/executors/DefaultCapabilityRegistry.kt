package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
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

    override fun intentsFor(state: ObjectState): List<Intent> {
        val accepting = capabilities.filter { it.accepts(state) }
        return Intent.entries.filter { intent -> accepting.any { intent in it.intents(state) } }
    }

    // Near-miss capabilities (#97): not accepting now, but one signal away. Ranked by priority and
    // capped so the hint informs rather than clutters the real action set.
    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> =
        capabilities.filterNot { it.accepts(state) }
            .sortedBy { it.meta.priority }
            .mapNotNull { c -> c.missing(state)?.let { LatentBubble(c.icon, c.label(state), it) } }
            .take(MAX_LATENT)

    override fun byId(id: CapabilityId): Capability =
        byIdMap[id] ?: error("No capability registered for id=${id.value}")

    private companion object {
        /** Show at most this many "почти доступно" hints — negotiation informs, it doesn't clutter. */
        const val MAX_LATENT = 2
    }
}
