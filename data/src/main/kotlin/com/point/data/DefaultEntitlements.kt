package com.point.data

import com.point.core.flow.Entitlements
import javax.inject.Inject

class DefaultEntitlements @Inject constructor() : Entitlements {
    override fun allowsPaid(): Boolean = true
}
