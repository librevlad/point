package com.point.core.model

/**
 * A persisted record of an object that passed through Point. The flow journal
 * (object → capability → object) is built from these; it powers History,
 * Favorite chains, Analytics and the training data for a learned Bubble Policy.
 * [ref] points at a persistent copy (outside the scratch store, which is wiped).
 */
data class HistoryEntry(
    val id: String,
    val mime: String,
    val kind: ObjectKind,
    val name: String?,
    val epochMillis: Long,
    val ref: ObjectRef,
)
