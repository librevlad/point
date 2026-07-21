package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef

/**
 * The private scratch store: immediate copy-in from the Share source,
 * materialisation of results, and cleanup at flow end.
 *
 * [ingest] takes the Share source as a **String** (the `android.net.Uri`
 * stringified at the app boundary), not a `Uri`. This keeps the contract — and
 * all of :core — free of Android types and unit-testable with fakes; the :data
 * implementation parses it back with `Uri.parse`. The [mime] comes from the
 * Share intent so the store can compute the initial (zero-signal) state.
 */
interface ObjectStore {

    /** Copy the shared source into scratch and wrap it as a [PointObject]. */
    suspend fun ingest(sourceUri: String, mime: String): PointObject

    /** Wrap an executor's already-materialised [ResultObject] as the next object. */
    suspend fun put(result: ResultObject): PointObject

    /** Allocate a fresh, empty file in scratch for an executor to write into.
     *  @param extension without the dot (e.g. "pdf", "jpg", "md"). */
    suspend fun newScratchFile(extension: String): ScratchRef

    /** Delete everything in scratch (call when the flow ends or is abandoned). */
    suspend fun clear()
}
