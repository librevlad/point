package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.WAYBILL_CONFIDENCE
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

/**
 * «Find waybill numbers» — the first extractor of the object pipeline (#222).
 *
 * The first enricher that returns **objects** rather than flags. Mirrors [EntityEnricher] in
 * shape: TEXT only, so it also covers screenshots, since OCR yields a TEXT object this runs on.
 *
 * On-device, rule-based, no model and no network — hence the cheap wave. That matters beyond
 * speed: the number the user actually came for must not depend on a key, a quota or a signal.
 *
 * **Why it exists.** ML Kit reads `20 4514 9154 9395` off a parcel screenshot and calls it a
 * phone; the plausibility filter then correctly drops it — 14 digits is not something you dial.
 * The judgement is right and stays. What was missing was anyone to pick the number up
 * afterwards, so the single most useful thing on the screen fell through the floor.
 */
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

        val objects = waybillNumbers(text).map { value ->
            PointObject(
                id = identifierId(obj.id, value),
                mime = "text/plain",
                // No file behind it: the value IS the content (#222).
                uri = ValueRef(value),
                state = ObjectState(KIND_IDENTIFIER),
                // Structural match only — no published check-digit algorithm went into the rule.
                confidence = WAYBILL_CONFIDENCE,
                sourceObjects = listOf(obj.id),
                creatorAction = CREATOR,
            )
        }
        if (objects.isEmpty()) return@withContext EnrichmentDelta()

        EnrichmentDelta(
            objects = objects,
            // Provenance, not meaning: «this number was read off that page». What the number
            // identifies and who issued it is for a classifier to say later, in code.
            relations = objects.map { Relation(it.id, RelationType.FOUND_IN, obj.id) },
        )
    }

    private companion object {
        const val MAX_CHARS = 20_000
        const val CREATOR = "identifier-enricher"

        /** Deterministic: re-running enrichment on the same object must not double the graph. */
        fun identifierId(sourceId: String, value: String) =
            "$sourceId:identifier:${value.filter(Char::isDigit)}"
    }
}
