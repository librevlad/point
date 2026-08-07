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
        val candidates = byCapability[capabilityId]
            ?: error("No realizer for capability=${capabilityId.value}")
        val chosen = policy.choose(state, candidates)
        return when {

            chosen.isEmpty() -> candidates.minByOrNull { it.meta.priority } ?: candidates.first()

            chosen.size == 1 -> chosen.first()

            else -> FallbackRealizer(capabilityId, chosen)
        }
    }

    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind == RealizerKind.CLOUD } ?: false

    private fun isPaywalled(capabilityId: CapabilityId): Boolean =
        !entitlements.allowsPaid() &&
            runCatching { registry.byId(capabilityId).meta.cost == Cost.PAID }.getOrDefault(false)
}

private class PaywallRealizer(override val capabilityId: CapabilityId) : Realizer {
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        ActionResult.Failure("Это Pro-функция — доступна по подписке", recoverable = true)
}
