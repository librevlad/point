package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import kotlinx.coroutines.flow.Flow

interface Enricher {

    val meta: EnricherMeta get() = EnricherMeta()

    fun appliesTo(state: ObjectState): Boolean

    suspend fun enrich(obj: PointObject): EnrichmentDelta
}

enum class EnrichCost { INSTANT, FAST, SLOW }

data class EnricherMeta(
    val cost: EnrichCost = EnrichCost.FAST,
    val mayYield: Set<Feature> = emptySet(),
    val label: String? = null,
    val mayYieldKinds: Set<ObjectKind> = emptySet(),
)

data class EnrichmentDelta(
    val features: Set<Feature> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val objects: List<PointObject> = emptyList(),
    val relations: List<Relation> = emptyList(),
)

data class EnrichmentUpdate(
    val features: Set<Feature>,
    val metadata: Map<String, String>,
    val running: List<String>,
    val objects: List<PointObject> = emptyList(),
    val relations: List<Relation> = emptyList(),
)

interface Enrichment {
    fun enrich(obj: PointObject): Flow<EnrichmentUpdate>
}

const val META_OCR_TEXT_REF = "ocr.text.ref"

const val META_OCR_ATOMS_REF = "ocr.atoms.ref"

const val META_CLOUD_ATOMS_REF = "cloud.atoms.ref"
