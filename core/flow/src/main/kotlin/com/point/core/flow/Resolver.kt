package com.point.core.flow

import com.point.core.model.CapabilityId

/**
 * Chooses a [Realizer] for a capability at execution time. MVP: the single local
 * realization. Later: pick by [CapabilityMeta] (cost/latency/network/auth) and
 * availability, or route to the Internet Capability Graph — all without any
 * change to the UI or Flow Graph.
 */
interface Resolver {
    fun realizerFor(capabilityId: CapabilityId): Realizer
}
