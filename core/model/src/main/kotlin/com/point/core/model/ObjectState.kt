package com.point.core.model

data class ObjectState(
    val kind: ObjectKind,
    val features: Set<Feature> = emptySet(),
) {
    fun with(feature: Feature): ObjectState = copy(features = features + feature)

    fun has(feature: Feature): Boolean = feature in features
}
