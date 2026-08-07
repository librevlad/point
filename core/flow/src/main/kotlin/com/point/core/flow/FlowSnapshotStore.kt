package com.point.core.flow

import com.point.core.model.FlowSnapshotFrame

interface FlowSnapshotStore {
    suspend fun save(frames: List<FlowSnapshotFrame>)

    suspend fun load(): List<FlowSnapshotFrame>

    suspend fun clear()
}
