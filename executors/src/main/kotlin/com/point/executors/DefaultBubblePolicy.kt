package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.model.ObjectState
import javax.inject.Inject

class DefaultBubblePolicy @Inject constructor() : BubblePolicy {
    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> =
        candidates.sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
}
