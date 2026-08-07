package com.point.core.model

data class PointObject(
    val id: String,
    val mime: String,
    val uri: ObjectRef,
    val state: ObjectState,
    val metadata: Map<String, String> = emptyMap(),
    @Deprecated(
        "Происхождение вместо уверенности: Provenance (#264). Одно число несравнимо между " +
            "ридерами, а экран всё равно переводил любое <1f в одно «возможно». Удалить после " +
            "мержа веток, живущих на этом поле.",
        ReplaceWith("provenance"),
    )
    val confidence: Float = 1f,
    val provenance: Provenance = Provenance.GIVEN,
    val sourceObjects: List<String> = emptyList(),
    val creatorAction: String? = null,
)
