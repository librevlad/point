package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

class DesktopRegistry(private val capabilities: Set<Capability>) : CapabilityRegistry {

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        bubblesFor(com.point.core.flow.GraphState(placeholder(state)))

    /**
     * Тот же принцип ранжирования, что и на телефоне (ADR-0001 §14): Intent поднимает
     * совпадающие по смыслу действия и никого не убирает; без Intent порядок прежний.
     *
     * Клаузула повторена, а не переиспользована: desktop не видит `:executors`
     * (стрелки модулей только вниз), а выносить одну строку в новый общий API запрещено объёмом.
     */
    override fun bubblesFor(graph: com.point.core.flow.GraphState): List<Bubble> {
        val state = graph.state
        val intent = graph.intent
        return capabilities

            .filterNot { it.meta.investigation }
            .filter { it.accepts(state) }
            .sortedWith(
                compareBy(
                    { if (intent == null || intent in it.intents(state)) 0 else 1 },
                    { it.meta.priority },
                    { it.id.value },
                ),
            )
            .map { Bubble(it.icon, it.label(state), it.id, it.produces(state) ?: state, yields = it.yields(state)) }
    }

    private fun placeholder(state: ObjectState) = com.point.core.model.PointObject(
        id = "desktop-probe",
        mime = "application/octet-stream",
        uri = com.point.core.model.ValueRef(""),
        state = state,
    )

    override fun all(): Collection<Capability> = capabilities

    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> = emptyList()

    override fun byId(id: CapabilityId): Capability = capabilities.first { it.id == id }
}

class DesktopResolver(
    realizers: Set<Realizer>,
    private val policy: com.point.core.flow.ExecutionPolicy = com.point.core.flow.DefaultExecutionPolicy(),
) : Resolver {
    private val byCapability = realizers.groupBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        realizerFor(capabilityId, ObjectState(com.point.core.model.ObjectKind.UNKNOWN))

    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == com.point.core.flow.RealizerKind.CLOUD } ?: false

    override fun realizerFor(capabilityId: CapabilityId, state: ObjectState): Realizer {
        val candidates = byCapability.getValue(capabilityId)
        return policy.choose(state, candidates).firstOrNull()
            ?: candidates.minByOrNull { it.meta.priority }
            ?: candidates.first()
    }
}
