package com.point.core.flow

import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

/**
 * One action, independent and replaceable.
 *
 * The [accepts] (input) and [produces] (output) contracts ARE the Flow Graph:
 * the bubbles for a state are exactly the executors whose `accepts(state)` is
 * true, and each bubble's next state comes from `produces(state)`. There is no
 * separate transition table to keep in sync.
 *
 * The executor also supplies its own presentation ([icon] / [title]) so the
 * registry can build a [com.point.core.model.Bubble] with no separate
 * id -> label map (which would be the very transition-table anti-pattern we avoid).
 */
interface Executor {

    val id: ExecutorId

    /** Icon key for the bubble; resolved to a vector by :core:ui. */
    val icon: String

    /** Bubble label. Depends on [state] because some actions are bidirectional
     *  (e.g. image/text -> PDF vs PDF -> extract text). */
    fun title(state: ObjectState): String

    /** Input contract: does this executor apply to [state]? */
    fun accepts(state: ObjectState): Boolean

    /** Output contract: the resulting state — a Flow Graph edge. */
    fun produces(state: ObjectState): ObjectState

    /**
     * Runs the action. MUST be cooperatively cancellable (structured
     * concurrency) — the user can Back out mid-step.
     *
     * @param amendment optional free-text the user added to this step.
     */
    suspend fun execute(input: PointObject, amendment: String? = null): ExecutorResult
}
