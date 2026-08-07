package com.point.core.model

data class FlowSnapshotFrame(
    val id: String,
    val kind: ObjectKind,
    val mime: String,
    val ref: String,
    val metadata: Map<String, String> = emptyMap(),
    val viaCapabilityId: String? = null,
    val viaTitle: String? = null,
)
