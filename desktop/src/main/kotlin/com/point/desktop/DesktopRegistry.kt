package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

class DesktopRegistry(
    private val capabilities: Set<Capability>,

    /** Дверь показывается, только если за ней есть исполнитель под этот объект (аудит, блок 1.6). */
    private val runnable: (CapabilityId, ObjectState) -> Boolean = { _, _ -> true },
) : CapabilityRegistry {

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        bubblesFor(com.point.core.flow.GraphState(placeholder(state)))

    /**
     * Тот же порядок, что и на телефоне, — и буквально тот же код (#840): правило живёт в
     * `:core:flow`, который виден обоим. Прежде клаузула была повторена здесь с оговоркой
     * «desktop не видит :executors»; копия правила нормы конституции — приглашение к
     * расхождению.
     */
    override fun bubblesFor(graph: com.point.core.flow.GraphState): List<Bubble> {
        val state = graph.state
        val intent = graph.intent
        return capabilities

            .filterNot { it.meta.investigation }
            .filter { it.accepts(state) }
            .filter { runnable(it.id, state) }
            .sortedWith(com.point.core.flow.byIntentThenPriority(state, intent))
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

/** Исполнителя нет — это честный отказ с причиной, а не молчаливый «первый попавшийся». */
class NoWayHere(val why: String) : IllegalStateException(why)

class DesktopResolver(
    realizers: Set<Realizer>,
    private val policy: com.point.core.flow.ExecutionPolicy = com.point.core.flow.DefaultExecutionPolicy(),

    /** Сетевая ли способность — знание живёт у неё, а не у исполнителя (#855). */
    private val capabilityIsNetwork: (CapabilityId) -> Boolean = { false },
) : Resolver {
    private val byCapability = realizers.groupBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        realizerFor(capabilityId, ObjectState(com.point.core.model.ObjectKind.UNKNOWN))

    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == com.point.core.flow.RealizerKind.CLOUD } ?: false

    fun canRun(capabilityId: CapabilityId, state: ObjectState): Boolean =
        byCapability[capabilityId].orEmpty().any { it.isAvailable() && it.accepts(state) }

    override fun realizerFor(capabilityId: CapabilityId, state: ObjectState): Realizer {
        val all = byCapability[capabilityId].orEmpty()

        // Негодный объект не уезжает наружу — то же правило, что и на телефоне (#855).
        // Компьютер сам помечает пустой файл при приёме, а наружу его до сих пор отпускал.
        val candidates = com.point.core.flow.staysHomeWhenUnfit(state, all) { realizer ->
            com.point.core.flow.sendsOutward(realizer, capabilityIsNetwork)
        }
        if (all.isNotEmpty() && candidates.isEmpty()) {
            throw NoWayHere(com.point.core.flow.UNFIT_DEFAULT_REASON)
        }

        // Аудит, блок 1.6: fallback на «первого попавшегося» превращал двери в обманки
        // («В PDF» на картинке всегда падало). Нет исполнителя — честная причина.
        return policy.choose(state, candidates).firstOrNull()
            ?: throw NoWayHere(
                candidates.firstNotNullOfOrNull { it.unavailableReason() }
                    ?: "На компьютере это действие для такого объекта не выполняется",
            )
    }
}
