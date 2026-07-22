package com.point.core.flow

/**
 * The paywall seam. Whether the user may run a paid (Pro) capability — one whose
 * [CapabilityMeta.cost] is [Cost.PAID]. The [Resolver] consults this and, when a Pro
 * capability is not entitled, hands back an upsell realizer instead of the real one —
 * so gating is a *realization* choice, never touching the UI or the Flow Graph.
 *
 * The default entitles everything (Point charges for nothing yet); a real
 * subscription / entitlement check drops in behind this interface to switch the
 * paywall on, without any consumer changing.
 */
fun interface Entitlements {
    fun allowsPaid(): Boolean
}
