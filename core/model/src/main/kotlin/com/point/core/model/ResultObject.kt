package com.point.core.model

data class ResultObject(
    val type: ObjectKind,
    val mime: String,
    val uri: ObjectRef,
    val metadata: Map<String, String> = emptyMap(),
)
