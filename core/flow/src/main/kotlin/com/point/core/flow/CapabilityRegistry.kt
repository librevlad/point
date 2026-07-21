package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState

/**
 * All registered capabilities (auto-registered — no hand-written map). The Flow
 * Graph is derived here: [bubblesFor] returns the accepting capabilities, ranked
 * by the [BubblePolicy], as bubbles. Adding a capability extends the graph with
 * zero other changes.
 */
interface CapabilityRegistry {
    fun bubblesFor(state: ObjectState): List<Bubble>
    fun byId(id: CapabilityId): Capability
}
