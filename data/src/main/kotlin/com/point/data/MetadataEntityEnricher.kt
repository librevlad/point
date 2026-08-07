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

class MetadataEntityEnricher @javax.inject.Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.INSTANT,
        mayYield = FEATURE_BY_SUFFIX.values.toSet() + com.point.core.flow.SEMANTIC_TYPES.values +
            Feature.HAS_WORD_LAYER,
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun appliesTo(state: ObjectState) = true

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {

        val (objects, relations) = entityObjects(obj, obj.metadata, creator = CREATOR)
        return EnrichmentDelta(
            features = FEATURE_BY_SUFFIX.mapNotNullTo(mutableSetOf()) { (suffix, feature) ->
                feature.takeIf { !obj.metadata[META_ENTITY_PREFIX + suffix].isNullOrBlank() }
            } + setOfNotNull(

                com.point.core.flow.SEMANTIC_TYPES[obj.metadata[com.point.core.flow.META_SEMANTIC_TYPE]],

                Feature.HAS_WORD_LAYER.takeIf {
                    !obj.metadata[com.point.core.flow.META_OCR_ATOMS_REF].isNullOrBlank() ||
                        !obj.metadata[com.point.core.flow.META_CLOUD_ATOMS_REF].isNullOrBlank()
                },
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
