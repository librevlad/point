package com.point.core.model

/**
 * The object's type plus a cheap set of feature signals.
 *
 * Bubble quality scales with state richness: a MIME-only state yields generic
 * bubbles; a feature-rich state yields smart ones. Executors decide whether they
 * [Executor.accepts] a state purely from this value — so state richness, not
 * a hard-coded table, is what makes the first screen good.
 */
data class ObjectState(
    val kind: ObjectKind,
    val features: Set<Feature> = emptySet(),
) {
    fun with(feature: Feature): ObjectState = copy(features = features + feature)

    fun has(feature: Feature): Boolean = feature in features
}
