package com.point.executors

import com.point.core.flow.ActionAvailability
import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Latency
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState
import javax.inject.Inject

class DefaultCapabilityRegistry @Inject constructor(
    private val capabilities: Set<@JvmSuppressWildcards Capability>,
    private val policy: BubblePolicy,

    private val availability: ActionAvailability = ActionAvailability { null },
) : CapabilityRegistry {

    private val byIdMap: Map<CapabilityId, Capability> = capabilities.associateBy { it.id }

    override fun all(): Collection<Capability> = capabilities

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        policy.rank(state, offered.filter { it.accepts(state) && blockerFor(it) == null })
            .map { c -> bubbleOf(c, state) }

    override fun bubblesFor(graph: com.point.core.flow.GraphState): List<Bubble> =
        policy.rank(graph, offered.filter { it.accepts(graph) && blockerFor(it) == null })
            .map { c -> bubbleOf(c, graph.state) }

    /**
     * Исследования человеку не предлагаются- их выбирает Discovery, а не Planner (ADR-0001 §11).
     */
    private val offered: List<Capability> = capabilities.filterNot { it.meta.investigation }

    private fun bubbleOf(c: Capability, state: ObjectState) = Bubble(
        icon = c.icon,
        title = c.label(state),
        capabilityId = c.id,
        expectedNextState = c.produces(state) ?: state,
        tier = tierOf(c.meta),
        intent = primaryIntentOf(c, state),

        yields = c.yields(state),
    )

    private fun tierOf(meta: CapabilityMeta): BubbleTier = when {
        meta.network -> BubbleTier.AI
        meta.latency == Latency.INSTANT -> BubbleTier.INSTANT
        else -> BubbleTier.SMART
    }

    private fun primaryIntentOf(c: Capability, state: ObjectState): Intent {
        val served = c.intents(state)
        return Intent.entries.firstOrNull { it in served } ?: Intent.UNDERSTAND
    }


    private fun missingFor(c: Capability, state: ObjectState): String? =
        if (c.accepts(state)) blockerFor(c) else c.missing(state)

    private fun blockerFor(c: Capability): String? = availability.blockerFor(c.id)

    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> {
        val hints = offered.sortedBy { it.meta.priority }
            .mapNotNull { c -> missingFor(c, state)?.let { LatentBubble(c.icon, c.label(state), it) } }

        val byReason = hints.groupBy(LatentBubble::missing).values.toList()
        val rounds = byReason.maxOfOrNull { it.size } ?: 0
        return (0 until rounds)
            .flatMap { round -> byReason.mapNotNull { it.getOrNull(round) } }
            .take(MAX_LATENT)
    }

    override fun byId(id: CapabilityId): Capability =
        byIdMap[id] ?: error("No capability registered for id=${id.value}")

    private companion object {

        const val MAX_LATENT = 2
    }
}
