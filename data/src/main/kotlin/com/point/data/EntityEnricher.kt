package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.addressFacts
import com.point.core.flow.expandAddressToLine
import com.point.core.flow.alternativesOf
import com.point.core.flow.altValue
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.provenanceOf
import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import com.point.core.flow.EntityExtractor
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
import com.point.core.flow.isBareClock
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class EntityEnricher @Inject constructor(
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.FAST,
        mayYield = setOf(
            Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS, Feature.HAS_DATE, Feature.HAS_CARD,
        ),
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext EnrichmentDelta()
        entityDelta(obj, extractor.extract(text), text)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

internal fun entityDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    text: String = "",
): EnrichmentDelta {
    val features = entities.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }
    val extracted = buildMap {

        entities.sortedBy { it.isBareClock() }.forEach { e ->
            e.type.asMetaKey()?.let { key ->

                val value = if (e.type == com.point.core.flow.EntityType.ADDRESS && text.isNotEmpty()) {
                    expandAddressToLine(e.value, text)
                } else {
                    e.value
                }
                putIfAbsent(key, value)
            }
        }
    }

    val ruled = if (META_ENTITY_ADDRESS in extracted) emptyMap() else addressFacts(text)
    val facts = extracted + ruled

    if (ruled.isNotEmpty()) features += Feature.HAS_ADDRESS
    val (objects, relations) = entityObjects(source, facts, creator = ENTITY_CREATOR)
    return EnrichmentDelta(features, facts, objects, relations)
}

internal const val ENTITY_CREATOR = "entity-enricher"

internal fun entityObjects(
    source: PointObject,
    facts: Map<String, String>,
    creator: String,
): Pair<List<PointObject>, List<Relation>> {

    if (source.state.kind in EXTRACTED_KINDS) return emptyList<PointObject>() to emptyList()

    val objects = ENTITY_KINDS.mapNotNull { (suffix, kindAndFeature) ->
        val key = META_ENTITY_PREFIX + suffix
        val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val (kind, feature) = kindAndFeature

        val alternatives = alternativesOf(facts, key)

        val slice = buildMap {
            put(key, value)
            if (alternatives.isNotEmpty()) {
                put(key + META_ALT_SUFFIX, altValue(alternatives))
            }

            facts[key + META_EVIDENCE_SUFFIX]?.let { put(key + META_EVIDENCE_SUFFIX, it) }
            facts[key + META_SOURCE_SUFFIX]?.let { put(key + META_SOURCE_SUFFIX, it) }
        }
        PointObject(
            id = "${source.id}:$suffix",
            mime = "text/plain",
            uri = ValueRef(value),
            state = ObjectState(kind, setOf(feature)),
            metadata = slice,
            provenance = provenanceOf(slice, key),
            sourceObjects = listOf(source.id),
            creatorAction = creator,
        )
    }
    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

private val ENTITY_KINDS: Map<String, Pair<ObjectKind, Feature>> = mapOf(
    "phone" to (KIND_PHONE to Feature.HAS_PHONE),
    "email" to (KIND_EMAIL to Feature.HAS_EMAIL),
    "url" to (KIND_URL to Feature.HAS_URL),
    "address" to (KIND_ADDRESS to Feature.HAS_ADDRESS),
    "date" to (KIND_DATE to Feature.HAS_DATE),
)
