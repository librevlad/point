package com.point.core.flow

import com.point.core.model.CapabilityId

fun interface ActionAvailability {

    fun blockerFor(id: CapabilityId): String?
}
