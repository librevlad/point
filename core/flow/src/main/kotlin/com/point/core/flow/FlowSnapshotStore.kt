package com.point.core.flow

import com.point.core.model.FlowSnapshotFrame

/**
 * The crash-proof journey journal (#7 — release blocker): a tiny record of the flow stack,
 * written on every step and cleared when the flow ends. Point is a journey of steps; losing
 * the journey to process death is the deepest trust break, so this seam exists.
 * Lives OUTSIDE scratch — scratch files themselves survive process death (clear() runs only
 * at flow end), which is what makes restoration possible at all.
 */
interface FlowSnapshotStore {
    suspend fun save(frames: List<FlowSnapshotFrame>)

    /** The persisted journey, oldest first; empty when there is nothing to restore. */
    suspend fun load(): List<FlowSnapshotFrame>

    suspend fun clear()
}
