package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind

interface PinnedActions {

    fun pinnedFor(kind: ObjectKind): CapabilityId?

    /**
     * Всё, что человек закрепил, — чтобы это было где увидеть и откуда снять (#821).
     *
     * Жест закрепления есть с самого начала, обзора не было: открепить, не вспомнив жеста,
     * человек не мог.
     */
    fun all(): Map<ObjectKind, CapabilityId> = emptyMap()

    suspend fun pin(kind: ObjectKind, id: CapabilityId)

    suspend fun unpin(kind: ObjectKind)
}
