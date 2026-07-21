package com.point.data

import com.point.core.flow.Enricher
import com.point.core.flow.Enrichment
import com.point.core.model.Feature
import com.point.core.model.PointObject
import javax.inject.Inject

/** Runs every applicable enricher (Hilt multibinding) and unions their features. */
class DefaultEnrichment @Inject constructor(
    private val enrichers: Set<@JvmSuppressWildcards Enricher>,
) : Enrichment {

    override suspend fun enrich(obj: PointObject): Set<Feature> = buildSet {
        for (enricher in enrichers) {
            if (enricher.appliesTo(obj.state)) addAll(enricher.enrich(obj))
        }
    }
}
