package com.point.core.flow

import com.point.core.model.HistoryEntry
import com.point.core.model.PointObject

/**
 * Persistent history of objects brought into Point (survives the scratch wipe).
 * Lets the user re-open a recent object and keep working — without going back to
 * the source app to share again. That is the metric: fewer app switches.
 */
interface HistoryStore {
    /** Persist an object as a history entry (a copy is kept). */
    suspend fun record(obj: PointObject)

    /** Fold what enrichment understood (features / entity values) into the existing
     *  entry for this object — so Home remembers objects by what they ARE (#114).
     *  Unknown id (e.g. a mid-flow object never recorded) is a no-op. */
    suspend fun update(obj: PointObject)

    /** Most-recent-first, de-duplicated, existing files only. */
    suspend fun recent(limit: Int = 30): List<HistoryEntry>

    /** Re-materialise a history entry as a fresh object for a new flow. */
    suspend fun open(entryId: String): PointObject?

    suspend fun clearAll()
}
