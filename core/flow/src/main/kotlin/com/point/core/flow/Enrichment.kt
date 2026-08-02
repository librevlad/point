package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
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

    /**
     * Expensive peek; returns what it discovered — features to ADD (never removes) and, since
     * #222, the objects it extracted. An enricher that yields objects is an **extractor**: it
     * answers «what is on this page», never «what should the user do about it».
     */
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
 *   knowledge would cost real work and change nothing.
 * @param mayYieldKinds every [com.point.core.model.ObjectKind] this enricher could extract
 *   (#222). The gate needs this because **an object is worth finding even when it opens no
 *   new action**: a waybill number adds nothing to the action list, yet it is the single most
 *   useful thing on a parcel screenshot. Judging by actions alone would skip exactly the
 *   extractors that matter most.
 * @param label short user-facing progress text (e.g. «Распознаю текст…») shown while
 *   this enricher works; null = too quick to be worth announcing.
 *
 * Both yield declarations empty = unknown, always run.
 */
data class EnricherMeta(
    val cost: EnrichCost = EnrichCost.FAST,
    val mayYield: Set<Feature> = emptySet(),
    val label: String? = null,
    val mayYieldKinds: Set<ObjectKind> = emptySet(),
)

/**
 * One enricher's findings: features to add, sidecar facts merged into the object's metadata
 * (e.g. a scratch ref to OCR'd text), and — since #222 — the objects and edges it extracted.
 *
 * [relations] may reference ids from [objects] and the id of the object being enriched.
 * Nothing here is authored by a model: an extractor answers with what it saw, and the
 * pipeline turns that into graph nodes in code.
 */
data class EnrichmentDelta(
    val features: Set<Feature> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val objects: List<PointObject> = emptyList(),
    val relations: List<Relation> = emptyList(),
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
    val objects: List<PointObject> = emptyList(),
    val relations: List<Relation> = emptyList(),
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

/** Metadata key: scratch ref of the full atom layer ([AtomCodec] format) read off an IMAGE (#257).
 *  The layer is evidence, not a representation: it survives even when the garbage gate hides the
 *  text from features, and it is what область-selection (#259) and re-reading will address. */
const val META_OCR_ATOMS_REF = "ocr.atoms.ref"

/**
 * Metadata key: scratch ref слоя атомов **второго**, облачного чтения страницы (#280).
 *
 * Свой ключ, а не [META_OCR_ATOMS_REF]. Затри облако офлайновый слой — и сравнивать два чтения
 * стало бы не с чем: расхождение ридеров и есть сигнал, где значение надёжно, а где идти
 * перечитывать. Один ключ на все облачные слои: кто именно прочитал, знает сам атом
 * ([Atom.reader]/[Atom.readerVersion]), и дублировать это отдельным ключом — заводить второй
 * источник правды о происхождении.
 */
const val META_CLOUD_ATOMS_REF = "cloud.atoms.ref"
