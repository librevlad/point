package com.point.data

import com.point.core.flow.Entitlements
import javax.inject.Inject

/**
 * Everything unlocked — Point charges for nothing yet, so every PAID capability
 * runs. Replace this with a real subscription / entitlement check (Play Billing,
 * server receipt, …) to switch the paywall on; nothing else changes.
 */
class DefaultEntitlements @Inject constructor() : Entitlements {
    override fun allowsPaid(): Boolean = true
}
