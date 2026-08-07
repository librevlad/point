package com.point.executors

import com.point.core.flow.ActionAvailability
import com.point.core.flow.Realizer
import com.point.core.model.CapabilityId
import javax.inject.Inject

class RealizerAvailability @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
) : ActionAvailability {

    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }
            .mapValues { (_, candidates) -> candidates.sortedBy { it.meta.priority } }

    override fun blockerFor(id: CapabilityId): String? {

        val candidates = byCapability[id] ?: return null
        if (candidates.any { it.isAvailable() }) return null
        return candidates.firstNotNullOfOrNull { it.unavailableReason() }
    }
}
