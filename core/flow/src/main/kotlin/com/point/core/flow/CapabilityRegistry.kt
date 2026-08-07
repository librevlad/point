package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState

interface CapabilityRegistry {
    fun bubblesFor(state: ObjectState): List<Bubble>

    fun intentsFor(state: ObjectState): List<Intent>

    fun latentBubblesFor(state: ObjectState): List<LatentBubble>

    fun byId(id: CapabilityId): Capability

    fun all(): Collection<Capability>
}
