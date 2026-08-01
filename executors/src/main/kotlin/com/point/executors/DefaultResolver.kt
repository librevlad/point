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
        val available = candidates.filter { it.isAvailable() }
        return when {
            // None available: return the top-ranked one so the failure surfaces from
            // perform() with a real message, not here.
            available.isEmpty() -> candidates.first()
            // One available: no wrapper — behaviour of single-realizer capabilities is
            // exactly as before.
            available.size == 1 -> available.first()
            // Several available: an output-based fallback chain (ranked, available).
            else -> FallbackRealizer(capabilityId, available)
        }
    }

    /**
     * Уходит ли объект с устройства при этом тапе: достаточно ОДНОГО не-локального
     * реализатора в цепочке. Именно так открылась дыра «Распознать текст»: capability
     * объявлена локальной, а её запасной путь — облачный, и согласие не спрашивалось.
     */
    override fun leavesDevice(capabilityId: CapabilityId): Boolean =
        byCapability[capabilityId]?.any { it.meta.kind != RealizerKind.LOCAL } ?: false

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
