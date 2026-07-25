package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind

/**
 * User rules (#66): «для такого объекта — сначала вот это». One pinned action per object
 * kind, set by a long-press on its bubble; the pinned action always ranks first, above
 * both declared priority and learned usage. The simplest useful rule engine — richer
 * rules (per-feature, per-source) can grow behind this same seam.
 */
interface PinnedActions {
    /** Synchronous — ranking happens on the первый экран path, like [CapabilityUsage.counts]. */
    fun pinnedFor(kind: ObjectKind): CapabilityId?

    suspend fun pin(kind: ObjectKind, id: CapabilityId)

    suspend fun unpin(kind: ObjectKind)
}
