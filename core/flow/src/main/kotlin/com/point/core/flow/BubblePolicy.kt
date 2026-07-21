package com.point.core.flow

import com.point.core.model.ObjectState

/**
 * Selects and orders which of the accepting capabilities actually become
 * bubbles. Capabilities may be hundreds; the user should see only a few. The
 * default is a deterministic sort; the interface is deliberately tiny so it can
 * be swapped for an ML/LLM policy later without touching the registry or UI.
 */
interface BubblePolicy {
    /** @param candidates capabilities that already accept the state. */
    fun rank(state: ObjectState, candidates: List<Capability>): List<Capability>
}
