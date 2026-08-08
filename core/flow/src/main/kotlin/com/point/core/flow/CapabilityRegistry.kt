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

    fun byId(id: CapabilityId): Capability

    fun all(): Collection<Capability>
}
