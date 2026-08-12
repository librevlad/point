package com.point.executors

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Cost
import com.point.core.flow.Entitlements
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.Resolver
import com.point.core.flow.staysHomeWhenUnfit
import com.point.core.flow.unusableReasonOf
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.PointObject
import javax.inject.Inject

class DefaultResolver @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
    private val registry: CapabilityRegistry,
    private val entitlements: Entitlements,

    private val policy: com.point.core.flow.ExecutionPolicy = com.point.core.flow.DefaultExecutionPolicy(),
) : Resolver {

    private companion object {

        val ANY_OBJECT = com.point.core.model.ObjectState(com.point.core.model.ObjectKind.UNKNOWN)
    }

    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        realizerFor(capabilityId, ANY_OBJECT)

    override fun realizerFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): Realizer {
        if (isPaywalled(capabilityId)) return PaywallRealizer(capabilityId)
        // Нет исполнителя — честный отказ словами, а не падение с идентификатором внутри
        // (#857): `FlowViewModel` показывает человеку `e.message`, и туда уезжало
        // `No realizer for capability=…`.
        val candidates = byCapability[capabilityId] ?: return NoWayRealizer(capabilityId)

        // Годность — часть состояния объекта (#684/#685): негодный исходник не уезжает
        // наружу ни ради распознавания, ни ради понимания. Правило одно на оба устройства
        // (#855) — местная попытка его не касается, она и раньше честно отказывала без
        // сетевого следа.
        val usable = staysHomeWhenUnfit(state, candidates) { sendsOutward(it) }
        if (usable.isEmpty()) return UnusableRealizer(capabilityId)

        val chosen = policy.choose(state, usable)
        return when {

            chosen.isEmpty() -> usable.minByOrNull { it.meta.priority } ?: usable.first()

            chosen.size == 1 -> chosen.first()

            else -> FallbackRealizer(capabilityId, chosen)
        }
    }

    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == RealizerKind.CLOUD } ?: false

    private fun sendsOutward(realizer: Realizer): Boolean =
        com.point.core.flow.sendsOutward(realizer) { id ->
            runCatching { registry.byId(id).meta.network }.getOrDefault(false)
        }

    private fun isPaywalled(capabilityId: CapabilityId): Boolean =
        !entitlements.allowsPaid() &&
            runCatching { registry.byId(capabilityId).meta.cost == Cost.PAID }.getOrDefault(false)
}

private class PaywallRealizer(override val capabilityId: CapabilityId) : Realizer {
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        ActionResult.Failure("Это Pro-функция — доступна по подписке", recoverable = true)
}

/**
 * Единственный местный кандидат оказался внешним, а объект уже негоден (#684/#685) — отказ
 * называется сразу, тем же словом, что и на экране, без сетевого похода.
 */
/** Способность объявлена, а исполнить её на этом устройстве нечем (#857). */
private class NoWayRealizer(override val capabilityId: CapabilityId) : Realizer {
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        ActionResult.Failure(com.point.core.flow.NO_WAY_HERE_REASON, recoverable = false)
}

private class UnusableRealizer(override val capabilityId: CapabilityId) : Realizer {
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        ActionResult.Failure(
            unusableReasonOf(input.metadata) ?: com.point.core.flow.UNFIT_DEFAULT_REASON,
            recoverable = true,
        )
}
