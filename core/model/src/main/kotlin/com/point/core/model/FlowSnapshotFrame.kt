package com.point.core.model

/**
 * One frame of the persisted flow journey (#7): just enough to re-materialise the stack
 * after process death. Features are NOT stored — enrichment re-derives them instantly
 * from the object bytes and the kept metadata (entity.* facts, OCR sidecar ref).
 */
data class FlowSnapshotFrame(
    val id: String,
    val kind: ObjectKind,
    val mime: String,
    val ref: String,
    val metadata: Map<String, String> = emptyMap(),
    val viaCapabilityId: String? = null,
    val viaTitle: String? = null,
)
