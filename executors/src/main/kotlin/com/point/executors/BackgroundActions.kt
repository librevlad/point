package com.point.executors

import com.point.core.flow.Capability
import com.point.core.model.ObjectState
import javax.inject.Inject

/** Дверь Hilt: «CutoutCapability» живёт в `:core:flow` (#1379). Члены руками, а не `by`: KSP. */
class CutoutCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.CutoutCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

/** Дверь Hilt: «BlurBgCapability» живёт в `:core:flow` (#1379). Члены руками, а не `by`: KSP. */
class BlurBgCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.BlurBgCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

/** Дверь Hilt: «ReplaceBgCapability» живёт в `:core:flow` (#1379). Члены руками, а не `by`: KSP. */
class ReplaceBgCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.ReplaceBgCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}
