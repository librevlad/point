package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.flow.Flow

/**
 * A cheap-ish asynchronous peek into an object's content that discovers extra
 * [Feature]s AFTER the first paint (progressive disclosure). Enrichers must not
 * run on the ≤300 ms first render — only zero-signal classification does.
 */
interface Enricher {
    /** Declared cost/yield — lets the [Enrichment] scheduler order and gate the work. */
    val meta: EnricherMeta get() = EnricherMeta()

    /** Cheap gate: is this enricher relevant to [state] at all? */
    fun appliesTo(state: ObjectState): Boolean

    /** Expensive peek; returns what it discovered (features to ADD — never removes). */
    suspend fun enrich(obj: PointObject): EnrichmentDelta
}

/** Cost tiers of an [Enricher] — the scheduler runs cheaper waves first. */
enum class EnrichCost { INSTANT, FAST, SLOW }

/**
 * What an [Enricher] declares about itself, so understanding an object is *scheduled*
 * (cheapest knowledge first), not just run wholesale.
 *
 * @param mayYield every feature this enricher could possibly flag. A SLOW enricher is
 *   skipped when none of them would open a new action on the current state — the
 *   knowledge would cost real work and change nothing. Empty = unknown, always run.
 * @param label short user-facing progress text (e.g. «Распознаю текст…») shown while
 *   this enricher works; null = too quick to be worth announcing.
 */
data class EnricherMeta(
    val cost: EnrichCost = EnrichCost.FAST,
    val mayYield: Set<Feature> = emptySet(),
    val label: String? = null,
)

/** One enricher's findings: features to add, plus sidecar facts (e.g. a scratch ref
 *  to OCR'd text) merged into the object's metadata. */
data class EnrichmentDelta(
    val features: Set<Feature> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A progressive snapshot of the enrichment run. [features]/[metadata] are cumulative;
 * [running] holds the labels of still-working enrichers (the UI's background-work
 * feedback). The final update always has an empty [running].
 */
data class EnrichmentUpdate(
    val features: Set<Feature>,
    val metadata: Map<String, String>,
    val running: List<String>,
)

/**
 * Runs the applicable [Enricher]s over an object, cheapest wave first, and emits an
 * [EnrichmentUpdate] as each one finishes — so bubbles appear progressively instead
 * of in one late batch.
 */
interface Enrichment {
    fun enrich(obj: PointObject): Flow<EnrichmentUpdate>
}

/** Metadata key: scratch ref of the text recognised inside an IMAGE object (OCR sidecar).
 *  Written by the OCR enricher; read by entity realizers and the OCR capability as a cache. */
const val META_OCR_TEXT_REF = "ocr.text.ref"
