package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

/**
 * The desktop's registry over the SAME contracts as the phone — a fixed priority
 * order is enough for v1 (no learning policy yet, deliberately).
 */
class DesktopRegistry(private val capabilities: Set<Capability>) : CapabilityRegistry {

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        capabilities
            .filter { it.accepts(state) }
            .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
            // #491: «что вернётся» проставляют оба реестра одинаково — иначе одно и то же
            // действие рассказывало бы о себе на телефоне и на ПК по-разному.
            .map { Bubble(it.icon, it.label(state), it.id, it.produces(state) ?: state, yields = it.yields(state)) }

    override fun all(): Collection<Capability> = capabilities

    override fun intentsFor(state: ObjectState): List<Intent> = emptyList()

    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> = emptyList()

    override fun byId(id: CapabilityId): Capability = capabilities.first { it.id == id }
}

/**
 * Тот же выбор исполнителя, что на телефоне, и тем же правилом (контракт 06.08.2026, И3).
 *
 * `ExecutionPolicy` живёт в общем ядре нарочно: две поверхности обязаны выбирать одинаково, иначе
 * одно и то же намерение поведёт себя по-разному, а виноватым окажется человек.
 */
class DesktopResolver(
    realizers: Set<Realizer>,
    private val policy: com.point.core.flow.ExecutionPolicy = com.point.core.flow.DefaultExecutionPolicy(),
) : Resolver {
    private val byCapability = realizers.groupBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        realizerFor(capabilityId, ObjectState(com.point.core.model.ObjectKind.UNKNOWN))

    /** Есть ли у намерения реализация, увозящая объект к чужому сервису. Выводится, не пишется. */
    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == com.point.core.flow.RealizerKind.CLOUD } ?: false

    override fun realizerFor(capabilityId: CapabilityId, state: ObjectState): Realizer {
        val candidates = byCapability.getValue(capabilityId)
        return policy.choose(state, candidates).firstOrNull()
            ?: candidates.minByOrNull { it.meta.priority }
            ?: candidates.first()
    }
}
