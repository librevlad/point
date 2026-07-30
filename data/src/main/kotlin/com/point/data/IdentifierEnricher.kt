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

        val (objects, relations) = identifierObjects(obj, text)
        if (objects.isEmpty()) return@withContext EnrichmentDelta()
        EnrichmentDelta(objects = objects, relations = relations)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

/**
 * Waybill numbers in already-read text → graph nodes.
 *
 * Shared with [OcrEnricher] on purpose. The rule first shipped as a TEXT-only enricher, and on a
 * real device that meant it **never ran**: what the user shares is an IMAGE, its text lives as an
 * OCR sidecar, and a TEXT object only appears if they tap «Распознать текст». The single most
 * useful thing on a parcel screenshot fell through the same floor twice, for a different reason
 * each time. Every path that produces text now goes through here.
 */
internal fun identifierObjects(
    source: PointObject,
    text: String,
): Pair<List<PointObject>, List<Relation>> {
    val objects = waybillNumbers(text).map { value ->
        PointObject(
            id = identifierId(source.id, value),
            mime = "text/plain",
            // No file behind it: the value IS the content (#222).
            uri = ValueRef(value),
            state = ObjectState(KIND_IDENTIFIER),
            // Structural match only — no published check-digit algorithm went into the rule.
            confidence = WAYBILL_CONFIDENCE,
            sourceObjects = listOf(source.id),
            creatorAction = IDENTIFIER_CREATOR,
        )
    }
    // Provenance, not meaning: «this number was read off that page». What the number identifies
    // and who issued it is for a classifier to say later, in code.
    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

internal const val IDENTIFIER_CREATOR = "identifier-enricher"

/** Deterministic: re-running enrichment on the same object must not double the graph. */
private fun identifierId(sourceId: String, value: String) =
    "$sourceId:identifier:${value.filter(Char::isDigit)}"
