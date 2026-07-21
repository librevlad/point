package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.ExecutorId
import com.point.core.model.ObjectState

/**
 * The set of all Executors (bound via Hilt multibinding, `@IntoSet`).
 *
 * [bubblesFor] derives the Flow Graph on demand: it returns a [Bubble] for every
 * executor whose `accepts(state)` is true. Adding an executor extends the graph
 * with zero changes to any map or to the UI.
 */
interface ExecutorRegistry {

    fun bubblesFor(state: ObjectState): List<Bubble>

    fun byId(id: ExecutorId): Executor
}
