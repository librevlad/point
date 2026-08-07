package com.point.core.flow

import com.point.core.model.HistoryEntry
import com.point.core.model.PointObject

interface HistoryStore {

    suspend fun record(obj: PointObject)

    suspend fun update(obj: PointObject)

    suspend fun recent(limit: Int = 30): List<HistoryEntry>

    suspend fun open(entryId: String): PointObject?

    suspend fun clearAll()
}
