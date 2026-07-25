package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

/**
 * Lights entity features straight from stored `entity.*` metadata — instant, no I/O.
 * This is how facts that arrived OUTSIDE a scan reach the graph: the LLM fallback's
 * findings (#64) and history re-opens keep their «Позвонить»/«Создать событие» without
 * re-running any engine.
 */
class MetadataEntityEnricher @javax.inject.Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.INSTANT,
        mayYield = FEATURE_BY_SUFFIX.values.toSet(),
    )

    override fun appliesTo(state: ObjectState) = true

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = EnrichmentDelta(
        features = FEATURE_BY_SUFFIX.mapNotNullTo(mutableSetOf()) { (suffix, feature) ->
            feature.takeIf { !obj.metadata[META_ENTITY_PREFIX + suffix].isNullOrBlank() }
        },
    )

    private companion object {
        val FEATURE_BY_SUFFIX = mapOf(
            "phone" to Feature.HAS_PHONE,
            "email" to Feature.HAS_EMAIL,
            "url" to Feature.HAS_URL,
            "address" to Feature.HAS_ADDRESS,
            "date" to Feature.HAS_DATE,
            "card" to Feature.HAS_CARD,
        )
    }
}
