package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.CapabilityId
import javax.inject.Inject

/**
 * MVP resolver: one realizer per capability (the local one). The seam for the
 * future — where a capability has several realizers (local / AI / cloud / ICG)
 * chosen by CapabilityMeta + availability — lives exactly here, invisible to UI.
 */
class DefaultResolver @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
) : Resolver {

    private val byCapability: Map<CapabilityId, Realizer> = realizers.associateBy { it.capabilityId }

    override fun realizerFor(capabilityId: CapabilityId): Realizer =
        byCapability[capabilityId] ?: error("No realizer for capability=${capabilityId.value}")
}
