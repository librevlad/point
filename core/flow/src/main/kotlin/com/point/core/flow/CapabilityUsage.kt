package com.point.core.flow

import com.point.core.model.CapabilityId

interface CapabilityUsage {
    fun counts(): Map<CapabilityId, Int>
    suspend fun record(id: CapabilityId)
}
