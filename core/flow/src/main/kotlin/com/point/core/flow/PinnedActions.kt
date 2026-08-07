package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind

interface PinnedActions {

    fun pinnedFor(kind: ObjectKind): CapabilityId?

    suspend fun pin(kind: ObjectKind, id: CapabilityId)

    suspend fun unpin(kind: ObjectKind)
}
