package com.point.core.model

/**
 * An in-flight object, living as its own copy inside the private scratch store.
 *
 * Every step operates on this copy (never on the original Share `content://` Uri,
 * whose read grant dies with the receiving Activity). Cleared when the flow ends.
 *
 * @param uri reference to this object's copy (an [ObjectRef]; local scratch today).
 * @param confidence how sure the producer is (#222). 1.0 for a file the user handed us; lower
 *   for something an extractor read off a page — a handwritten plate is a [Vehicle] identifier
 *   just like a printed one, only less certain. Drives the ⚠ mark and consensus.
 * @param sourceObjects ids this object was derived from — the provenance edge of the graph.
 *   Empty for the object the user shared.
 * @param creatorAction which extractor or capability produced it, so a wrong reading can be
 *   traced back to the component that made it.
 */
data class PointObject(
    val id: String,
    val mime: String,
    val uri: ObjectRef,
    val state: ObjectState,
    val metadata: Map<String, String> = emptyMap(),
    val confidence: Float = 1f,
    val sourceObjects: List<String> = emptyList(),
    val creatorAction: String? = null,
)
