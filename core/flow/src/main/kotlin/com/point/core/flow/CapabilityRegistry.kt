package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

/**
 * All registered capabilities (auto-registered — no hand-written map). The Flow
 * Graph is derived here: [bubblesFor] returns the accepting capabilities, ranked
 * by the [BubblePolicy], as bubbles. Adding a capability extends the graph with
 * zero other changes.
 */
interface CapabilityRegistry {
    fun bubblesFor(state: ObjectState): List<Bubble>

    /** Applicable user intents for [state] — the intents served by ≥1 accepting
     *  capability, in [Intent] declaration order. The intent-first surface. */
    fun intentsFor(state: ObjectState): List<Intent>

    /** Almost-applicable capabilities (#97 negotiation): those that don't accept [state] but declare
     *  a [Capability.missing] hint for it. Capped so it informs without cluttering. */
    fun latentBubblesFor(state: ObjectState): List<LatentBubble>

    fun byId(id: CapabilityId): Capability
}
