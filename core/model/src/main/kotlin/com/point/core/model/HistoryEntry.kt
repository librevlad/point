package com.point.core.model

data class HistoryEntry(
    val id: String,
    val mime: String,
    val kind: ObjectKind,
    val name: String?,
    val epochMillis: Long,
    val ref: ObjectRef,

    val features: Set<Feature> = emptySet(),

    val metadata: Map<String, String> = emptyMap(),
)
