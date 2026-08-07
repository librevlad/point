package com.point.core.flow

import com.point.core.model.ObjectState

interface BubblePolicy {

    fun rank(state: ObjectState, candidates: List<Capability>): List<Capability>
}
