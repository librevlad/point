package com.point.core.flow

import com.point.core.model.HistoryEntry
import com.point.core.model.PointObject

interface HistoryStore {

    suspend fun record(obj: PointObject)

    suspend fun update(obj: PointObject)

    suspend fun recent(limit: Int = 30): List<HistoryEntry>

    suspend fun open(entryId: String): PointObject?

    /**
     * Убрать одну запись со всем, что она оставила (#543): и сам файл, и копии улик рядом с ним.
     * Половинчатое удаление — дефект: человек убирал не строку списка, а распознанный текст.
     */
    suspend fun remove(entryId: String)

    suspend fun clearAll()
}
