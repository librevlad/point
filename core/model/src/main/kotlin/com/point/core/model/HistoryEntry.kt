package com.point.core.model

/**
 * A persisted record of an object that passed through Point. The flow journal
 * (object → capability → object) is built from these; it powers History,
 * Analytics and the training data for a learned Bubble Policy.
 * [ref] points at a persistent copy (outside the scratch store, which is wiped).
 */
data class HistoryEntry(
    val id: String,
    val mime: String,
    val kind: ObjectKind,
    val name: String?,
    val epochMillis: Long,
    val ref: ObjectRef,
    /** What Point understood about the object (#114) — appended once enrichment finished. */
    val features: Set<Feature> = emptySet(),
    /** First value per entity kind, without the `entity.` prefix (phone → «+380…»). */
    val entities: Map<String, String> = emptyMap(),
)
