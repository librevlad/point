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
    val capabilityId: CapabilityId,
    val expectedNextState: ObjectState,
    val tier: BubbleTier = BubbleTier.SMART,
)

/**
 * The action's visual weight class (#114) — derived from the capability's meta, never
 * hand-assigned: INSTANT (local, immediate — copy/share/open), SMART (real on-device
 * work — recognise/transform), AI (leaves the device — cloud models). The three levels
 * look different on screen so the user can feel an action's nature before tapping.
 */
enum class BubbleTier { INSTANT, SMART, AI }
