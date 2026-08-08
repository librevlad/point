package com.point.core.flow

import com.point.core.model.ObjectState

interface BubblePolicy {

    fun rank(state: ObjectState, candidates: List<Capability>): List<Capability>

    /**
     * Порядок действий по всему состоянию (ADR-0001 §14 — Action ranking).
     *
     * Intent влияет на порядок и не убирает действия из списка (Конституция §8).
     */
    fun rank(graph: GraphState, candidates: List<Capability>): List<Capability> =
        rank(graph.state, candidates)
}
