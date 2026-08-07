package com.point.core.flow

import com.point.core.model.ObjectState

fun interface ExecutionPolicy {

    fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer>
}

class DefaultExecutionPolicy : ExecutionPolicy {
    override fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer> =
        candidates
            .filter { it.isAvailable() && it.accepts(state) }

            .sortedWith(compareBy({ it.meta.priority }, { it::class.java.name }))
}
