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
 * Lights features straight from stored metadata — instant, no I/O.
 * This is how facts that arrived OUTSIDE a scan reach the graph: the LLM fallback's
 * findings (#64) and history re-opens keep their «Позвонить»/«Создать событие» without
 * re-running any engine.
 *
 * Тем же путём переживает перезапуск и **слой слов** (#279): признаки не персистятся, а
 * прочитанная страница — да, ссылкой в метаданных. Без этого «Найти в документе» пропадало бы
 * ровно на объекте, который уже прочитан, и вернуть его мог бы только повторный прогон движка —
 * работа, уже сделанная однажды.
 */
class MetadataEntityEnricher @javax.inject.Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.INSTANT,
        mayYield = FEATURE_BY_SUFFIX.values.toSet() + com.point.core.flow.SEMANTIC_TYPES.values +
            Feature.HAS_WORD_LAYER,
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
                // Прочитанная страница (#279). Любой слой годится: офлайновый и облачный (#280)
                // лежат в разных ключах именно для того, чтобы не затирать друг друга, а
                // подсветить находку можно по любому из них.
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
