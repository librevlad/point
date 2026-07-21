package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.ExecutorRegistry
import com.point.core.model.Bubble
import com.point.core.model.ExecutorId
import com.point.core.model.ObjectState
import javax.inject.Inject

/**
 * Derives the Flow Graph from the set of executors (Hilt multibinding).
 * The bubbles for a state ARE the executors that accept it — no stored map.
 */
class DefaultExecutorRegistry @Inject constructor(
    private val executors: Set<@JvmSuppressWildcards Executor>,
) : ExecutorRegistry {

    private val byIdMap: Map<ExecutorId, Executor> = executors.associateBy { it.id }

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        executors
            .filter { it.accepts(state) }
            .sortedWith(compareBy({ it.order }, { it.id.value })) // deterministic order
            .map { e ->
                Bubble(
                    icon = e.icon,
                    title = e.title(state),
                    executorId = e.id,
                    expectedNextState = e.produces(state),
                )
            }

    override fun byId(id: ExecutorId): Executor =
        byIdMap[id] ?: error("No executor registered for id=${id.value}")
}
