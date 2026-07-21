package com.point.core.model

/**
 * A single action offered on the current object.
 *
 * The set of bubbles for a state IS the Flow Graph, derived from Executor
 * contracts — there is no separate stored transition map. [expectedNextState]
 * is the graph edge this bubble represents (from `Executor.produces`).
 *
 * @param icon icon key, resolved to a drawable/vector by :core:ui.
 */
data class Bubble(
    val icon: String,
    val title: String,
    val executorId: ExecutorId,
    val expectedNextState: ObjectState,
)
