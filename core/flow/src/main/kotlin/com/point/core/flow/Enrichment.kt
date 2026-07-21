package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

/**
 * A cheap-ish asynchronous peek into an object's content that discovers extra
 * [Feature]s AFTER the first paint (progressive disclosure). Enrichers must not
 * run on the ≤300 ms first render — only zero-signal classification does.
 */
interface Enricher {
    /** Cheap gate: is this enricher relevant to [state] at all? */
    fun appliesTo(state: ObjectState): Boolean

    /** Expensive peek; returns the features to ADD (never removes). */
    suspend fun enrich(obj: PointObject): Set<Feature>
}

/** Runs every applicable [Enricher] and unions the features they discover. */
interface Enrichment {
    suspend fun enrich(obj: PointObject): Set<Feature>
}
