package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.GraphState
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import javax.inject.Inject

/**
 * Способность живёт в `:core:flow` — одна на телефон и компьютер (#1379). Здесь только дверь
 * Hilt; члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе
 * роняет KSP всего модуля.
 */
class TakeFragmentCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.TakeFragmentCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
}
