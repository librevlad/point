package com.point.executors

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Cost
import com.point.core.flow.Entitlements
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Chooses a realizer for a capability. Several realizers may implement the same
 * capability (local / AI / cloud / ICG); candidates are ranked by `meta.priority`
 * then `meta.kind` (local before cloud before remote). One available realizer is
 * returned directly; several are wrapped in a [FallbackRealizer] output-based chain —
 * each defers to the next on a recoverable failure (e.g. on-device OCR that recognises
 * nothing hands off to the cloud). This is the seam for cloud/ICG — invisible to UI.
 *
 * It is also the paywall seam: a PAID capability the user is not entitled to
 * resolves to a [PaywallRealizer] upsell instead of the real realizer. Gating is a
 * *realization* choice, so the Flow Graph and bubbles are untouched — the Pro action
 * is still discoverable; tapping it explains it is Pro.
 */
class DefaultResolver @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
    private val registry: CapabilityRegistry,
    private val entitlements: Entitlements,
    /** Чем и где выполнять — шов ADR-0001; правило одно на обе поверхности. */
    private val policy: com.point.core.flow.ExecutionPolicy = com.point.core.flow.DefaultExecutionPolicy(),
) : Resolver {

    private companion object {
        /** «Про объект ничего не известно» — для старого [realizerFor] без состояния. */
        val ANY_OBJECT = com.point.core.model.ObjectState(com.point.core.model.ObjectKind.UNKNOWN)
    }

    // Порядок задают сами реализации через `meta.priority`. Второго ключа сортировки по
    // `meta.kind` здесь больше нет (контракт 06.08.2026, И3): «локальный раньше облачного раньше
    // удалённого» было философией, объявленной от имени всех будущих реализаций сразу. Сегодняшнее
    // поведение цепочки чтения не изменилось — его держат объявленные приоритеты самих
    // реализаций, и это их свойство, а не закон архитектуры.
    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        realizerFor(capabilityId, ANY_OBJECT)

    override fun realizerFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): Realizer {
        if (isPaywalled(capabilityId)) return PaywallRealizer(capabilityId)
        val candidates = byCapability[capabilityId]
            ?: error("No realizer for capability=${capabilityId.value}")
        val chosen = policy.choose(state, candidates)
        return when {
            // Никто не взялся: отдаём первого, чтобы отказ прозвучал из perform() словами, а не
            // исключением отсюда.
            chosen.isEmpty() -> candidates.minByOrNull { it.meta.priority } ?: candidates.first()
            // Один — без обёртки: поведение способностей с единственной реализацией не меняется.
            chosen.size == 1 -> chosen.first()
            // Несколько — цепочка с запасным путём, в выбранном порядке.
            else -> FallbackRealizer(capabilityId, chosen)
        }
    }

    /**
     * Уходит ли объект с устройства при этом тапе: достаточно ОДНОГО не-локального
     * реализатора в цепочке. Именно так открылась дыра «Распознать текст»: capability
     * объявлена локальной, а её запасной путь — облачный, и согласие не спрашивалось.
     */
    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == RealizerKind.CLOUD } ?: false

    /** A PAID capability blocks only when the user is not entitled. An unknown id
     *  (no capability registered) is never gated — it resolves as before. */
    private fun isPaywalled(capabilityId: CapabilityId): Boolean =
        !entitlements.allowsPaid() &&
            runCatching { registry.byId(capabilityId).meta.cost == Cost.PAID }.getOrDefault(false)
}

/** Stands in for a Pro realizer when the user is not entitled: a recoverable upsell
 *  instead of running the action. */
private class PaywallRealizer(override val capabilityId: CapabilityId) : Realizer {
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        ActionResult.Failure("Это Pro-функция — доступна по подписке", recoverable = true)
}
