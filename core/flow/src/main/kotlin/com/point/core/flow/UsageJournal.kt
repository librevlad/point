package com.point.core.flow

/** What happened, anonymised — no object content, no PII, and it never leaves the device. */
enum class UsageEventType { SHARED, ACTION, COMPLETED, FAILED, EDGE }

data class UsageEvent(val type: UsageEventType, val detail: String = "")

/** An [UsageEventType.EDGE] detail: the actually-traversed graph edge, kinds and id only. */
fun edgeDetail(fromKind: String, capabilityId: String, toKind: String): String =
    "$fromKind>$capabilityId>$toKind"

/**
 * The aggregate picture for the North Star ("fewer app switches"): how many objects
 * were brought in, how many actions taken, how many carried to a terminal in-app,
 * how many died in a failure (#117 — the standard metrics).
 */
data class UsageSummary(
    val objects: Int,
    val actions: Int,
    val completed: Int,
    val failed: Int = 0,
) {
    /** Chain depth — more actions per object means more done in Point without leaving. */
    val actionsPerObject: Double get() = if (objects == 0) 0.0 else actions.toDouble() / objects
}

/**
 * Private, on-device, consent-gated usage journal. Turns the North Star slogan into a
 * measurable signal WITHOUT sending anything anywhere: events are anonymised and stay
 * on the device. Nothing is recorded until the user opts in via [setEnabled]; turning
 * it off wipes the data.
 */
interface UsageJournal {
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun record(event: UsageEvent)
    suspend fun summary(): UsageSummary

    /**
     * The graph metrics (#117): counts of actually-traversed edges, keyed by
     * [edgeDetail]. This is USAGE of the derived Flow Graph, not a stored transition
     * table — the graph itself still comes from capabilities alone.
     */
    suspend fun graph(): Map<String, Int>
    suspend fun clear()
}
