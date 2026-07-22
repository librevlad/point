package com.point.core.model

/**
 * A saved sequence of capabilities (e.g. image → PDF → share). Replaying it on a
 * new object collapses a repeated multi-step workflow into one tap — the metric:
 * fewer app switches. Built from the flow journal (which capability produced each
 * frame).
 */
data class FavoriteChain(
    val id: String,
    val name: String,
    val steps: List<CapabilityId>,
)
