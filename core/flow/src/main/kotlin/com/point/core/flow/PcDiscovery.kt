package com.point.core.flow

import kotlinx.coroutines.flow.Flow

/** A Point-for-PC instance seen in the local network (#147 slice C). */
data class DiscoveredPc(val name: String, val host: String, val port: Int)

/**
 * LAN discovery of `_point-pc._tcp` services. Emits the current list as it changes;
 * collection stops the underlying scan. Discovery is SUGAR over manual host:port —
 * flaky networks (AP isolation, emulator NAT) must never block pairing.
 */
fun interface PcDiscovery {
    fun discover(): Flow<List<DiscoveredPc>>
}
