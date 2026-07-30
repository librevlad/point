package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
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
        mayYield = FEATURE_BY_SUFFIX.values.toSet() + com.point.core.flow.SEMANTIC_TYPES.values,
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun appliesTo(state: ObjectState) = true

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {
        // Same builder, same ids as the live extractor (#222): a fact that arrives both ways
        // collapses to one node instead of appearing twice.
        val (objects, relations) = entityObjects(obj, obj.metadata, creator = CREATOR)
        return EnrichmentDelta(
            features = FEATURE_BY_SUFFIX.mapNotNullTo(mutableSetOf()) { (suffix, feature) ->
                feature.takeIf { !obj.metadata[META_ENTITY_PREFIX + suffix].isNullOrBlank() }
            } + setOfNotNull(
                // The semantic level (#89): a stored recognised type IS a feature of the object.
                com.point.core.flow.SEMANTIC_TYPES[obj.metadata[com.point.core.flow.META_SEMANTIC_TYPE]],
            ),
            objects = objects,
            relations = relations,
        )
    }

    private companion object {
        const val CREATOR = "metadata-entity-enricher"

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
