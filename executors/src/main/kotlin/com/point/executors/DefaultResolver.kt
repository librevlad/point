package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import javax.inject.Inject

/**
 * Chooses a realizer for a capability. Several realizers may implement the same
 * capability (local / AI / cloud / ICG); candidates are ranked by
 * `meta.priority` then `meta.kind` (local before cloud before remote), and the
 * first **available** one wins. This is the seam for cloud/ICG — invisible to UI.
 * Today each capability has exactly one local realizer, so selection is a no-op.
 */
class DefaultResolver @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
) : Resolver {

    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }
            .mapValues { (_, candidates) ->
                candidates.sortedWith(compareBy({ it.meta.priority }, { it.meta.kind.ordinal }))
            }

    override fun realizerFor(capabilityId: CapabilityId): Realizer {
        val candidates = byCapability[capabilityId]
            ?: error("No realizer for capability=${capabilityId.value}")
        // Prefer the first available candidate; fall back to the top-ranked one so
        // the failure surfaces from perform() with a real message, not here.
        return candidates.firstOrNull { it.isAvailable() } ?: candidates.first()
    }
}
