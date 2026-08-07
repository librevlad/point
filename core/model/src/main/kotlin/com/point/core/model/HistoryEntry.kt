package com.point.core.model

data class HistoryEntry(
    val id: String,
    val mime: String,
    val kind: ObjectKind,
    val name: String?,
    val epochMillis: Long,
    val ref: ObjectRef,

    val features: Set<Feature> = emptySet(),

    val entities: Map<String, String> = emptyMap(),
)
