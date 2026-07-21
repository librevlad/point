package com.point.core.flow

/**
 * Declarative metadata used by the Bubble Policy (ranking), the Resolver
 * (choosing a realization), the paywall (gating Pro capabilities), and the
 * first-screen budget (keeping network/slow capabilities off the ≤300 ms paint).
 * One set of fields, several jobs — and the seam the Internet Capability Graph
 * will read later, without any of these consumers changing.
 */
data class CapabilityMeta(
    /** Lower = earlier bubble. Ties broken by capability id (deterministic). */
    val priority: Int = 50,
    val cost: Cost = Cost.LOCAL,
    val latency: Latency = Latency.INSTANT,
    /** Needs the network (so: not on the instant first screen). */
    val network: Boolean = false,
    /** Needs a key / login (BYOK, cloud auth). */
    val auth: Boolean = false,
)

enum class Cost { FREE, LOCAL, PAID }

enum class Latency { INSTANT, FAST, SLOW }
