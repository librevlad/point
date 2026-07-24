package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/**
 * A capability = **what** can be done to an object, declared independently of
 * **how** it is done. Point (UI, Flow Graph, Bubble Policy) knows only this — it
 * never knows the realization (local / AI / cloud / future Internet Capability
 * Graph). The set of capabilities whose [accepts] is true for a state IS the
 * Flow Graph; there is no separate transition table.
 *
 * A capability carries no execution logic — that lives in a [Realizer], chosen at
 * run time by a [Resolver]. This is the seam that lets the same capability be
 * performed differently without touching UI or graph.
 */
interface Capability {

    val id: CapabilityId

    /** Icon key resolved to a vector by :core:ui. */
    val icon: String

    /** Ranking / routing hints (priority, cost, latency, network, auth). */
    val meta: CapabilityMeta get() = CapabilityMeta()

    /** Bubble label (may depend on state, though most are constant). */
    fun label(state: ObjectState): String

    /** Input contract: does this capability apply to [state]? */
    fun accepts(state: ObjectState): Boolean

    /**
     * Advisory next state — a hint for graph preview / the bubble. NOT
     * authoritative: after execution the engine re-classifies the actual produced
     * object. `null` means unknown (e.g. AI can produce anything).
     */
    fun produces(state: ObjectState): ObjectState?

    /**
     * Which user [Intent]s this capability serves for [state] — the middle term of
     * `Object → Intent → … → Object`. The UI shows *intents*, not capabilities; a
     * capability is one way to fulfil an intent.
     *
     * Default derives from [produces]: a terminal action returns the **same** state
     * (`produces === state`, e.g. Share/Save) and SENDs; a TEXT (or an unknown AI)
     * output helps you UNDERSTAND; anything else PREPAREs a new artifact — including a
     * *same-kind transform* (scan/compress: image → a fresh image), which returns a
     * new `ObjectState` and so is told apart from a terminal by identity, not value.
     * Override only when the derived intent is wrong.
     */
    fun intents(state: ObjectState): Set<Intent> {
        val next = produces(state)
        return when {
            next == null -> setOf(Intent.UNDERSTAND)
            next === state -> setOf(Intent.SEND)
            next.kind == ObjectKind.TEXT -> setOf(Intent.UNDERSTAND)
            else -> setOf(Intent.PREPARE)
        }
    }

    /**
     * Capability negotiation (#97). When this capability does **not** [accepts] [state] but is one
     * concrete signal away, return a short phrase for **what's missing** (e.g. "нужен номер",
     * "сначала распознайте текст"); the UI shows it as a dimmed "почти доступно" hint so the user
     * learns the power exists and how to unlock it. Return null (default) to stay hidden — only
     * declare a hint where it is genuinely close, never for every unrelated object (no clutter).
     */
    fun missing(state: ObjectState): String? = null
}
