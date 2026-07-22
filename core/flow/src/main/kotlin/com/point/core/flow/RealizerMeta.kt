package com.point.core.flow

/**
 * Per-realization traits the [Resolver] uses to choose among several [Realizer]s
 * that offer the same [Capability]. Today every capability has one local
 * realizer, so this is dormant — but it is the seam where cloud / Internet
 * Capability Graph realizations plug in without any change above the resolver.
 */
data class RealizerMeta(
    /** Lower wins when several realizers implement the same capability. */
    val priority: Int = 50,
    val kind: RealizerKind = RealizerKind.LOCAL,
)

/** Where a realization runs. [REMOTE] is reserved for the future ICG. */
enum class RealizerKind { LOCAL, CLOUD, REMOTE }
