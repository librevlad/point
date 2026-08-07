package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.amountFacts
import com.point.core.flow.geoFacts
import com.point.core.flow.meterFacts
import com.point.core.flow.provenanceOf
import com.point.core.flow.receiptFacts
import com.point.core.flow.trackFacts
import com.point.core.flow.waybillNumbers
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class IdentifierEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.FAST,
        mayYieldKinds = setOf(KIND_IDENTIFIER),
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext EnrichmentDelta()

        val facts = trackFacts(text)
        val (objects, relations) = identifierObjects(obj, text, facts)

        val ruleFacts = facts + meterFacts(text) + geoFacts(text) + amountFacts(text) + receiptFacts(text)
        if (objects.isEmpty() && ruleFacts.isEmpty()) return@withContext EnrichmentDelta()

        EnrichmentDelta(objects = objects, relations = relations, metadata = ruleFacts)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

internal fun identifierObjects(
    source: PointObject,
    text: String,
    facts: Map<String, String> = trackFacts(text),
): Pair<List<PointObject>, List<Relation>> {
    val objects = waybillNumbers(text).map { value ->
        val slice = buildMap {
            put(META_ENTITY_TRACK, value)
            facts[META_ENTITY_TRACK + META_SOURCE_SUFFIX]
                ?.let { put(META_ENTITY_TRACK + META_SOURCE_SUFFIX, it) }
            facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX]
                ?.let { put(META_ENTITY_TRACK + META_EVIDENCE_SUFFIX, it) }
        }
        PointObject(
            id = identifierId(source.id, value),
            mime = "text/plain",

            uri = ValueRef(value),
            state = ObjectState(KIND_IDENTIFIER),
            metadata = slice,

            provenance = provenanceOf(slice, META_ENTITY_TRACK),
            sourceObjects = listOf(source.id),
            creatorAction = IDENTIFIER_CREATOR,
        )
    }

    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

internal const val IDENTIFIER_CREATOR = "identifier-enricher"

private fun identifierId(sourceId: String, value: String) =
    "$sourceId:identifier:${value.filter(Char::isLetterOrDigit).uppercase()}"
