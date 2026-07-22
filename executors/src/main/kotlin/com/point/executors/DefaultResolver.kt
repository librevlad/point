package com.point.executors

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Cost
import com.point.core.flow.Entitlements
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Chooses a realizer for a capability. Several realizers may implement the same
 * capability (local / AI / cloud / ICG); candidates are ranked by `meta.priority`
 * then `meta.kind` (local before cloud before remote), and the first **available**
 * one wins. This is the seam for cloud/ICG — invisible to UI.
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
) : Resolver {

    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }
            .mapValues { (_, candidates) ->
                candidates.sortedWith(compareBy({ it.meta.priority }, { it.meta.kind.ordinal }))
            }

    override fun realizerFor(capabilityId: CapabilityId): Realizer {
        if (isPaywalled(capabilityId)) return PaywallRealizer(capabilityId)
        val candidates = byCapability[capabilityId]
            ?: error("No realizer for capability=${capabilityId.value}")
        // Prefer the first available candidate; fall back to the top-ranked one so
        // the failure surfaces from perform() with a real message, not here.
        return candidates.firstOrNull { it.isAvailable() } ?: candidates.first()
    }

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
