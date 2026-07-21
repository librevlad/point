package com.point.core.model

/**
 * An in-flight object, living as its own copy inside the private scratch store.
 *
 * Every step operates on this copy (never on the original Share `content://` Uri,
 * whose read grant dies with the receiving Activity). Cleared when the flow ends.
 *
 * @param uri reference to this object's copy in the scratch store.
 */
data class PointObject(
    val id: String,
    val mime: String,
    val uri: ScratchRef,
    val state: ObjectState,
    val metadata: Map<String, String> = emptyMap(),
)
