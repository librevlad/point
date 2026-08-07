package com.point.core.flow

import com.point.core.model.CapabilityId

interface Resolver {
    fun realizerFor(capabilityId: CapabilityId): Realizer

    fun realizerFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): Realizer =
        realizerFor(capabilityId)

    fun leavesDevice(capabilityId: CapabilityId): Boolean = true
}
