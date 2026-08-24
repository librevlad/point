package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

interface CapabilityRegistry {
    fun bubblesFor(state: ObjectState): List<Bubble>

    /**
     * Действия по всему состоянию, а не по одной форме объекта (ADR-0001 §14).
     */
    fun bubblesFor(graph: GraphState): List<Bubble> = bubblesFor(graph.state)

    fun latentBubblesFor(state: ObjectState): List<LatentBubble>

    /**
     * Подсказка видит то же знание, что и дверь (#1101).
     *
     * Подсказка говорит, чего действию не хватает: «Найти в документе — разложите на
     * страницы». У объекта, которому чтения не предлагаются вовсе, такая строка учит
     * готовиться к тому, что одной строкой выше уже отнято. Знание о годности живёт в
     * фактах объекта, поэтому спрашивать подсказки нужно по графу, а не по одной форме.
     */
    fun latentBubblesFor(graph: GraphState): List<LatentBubble> = latentBubblesFor(graph.state)

    fun byId(id: CapabilityId): Capability

    fun all(): Collection<Capability>
}
