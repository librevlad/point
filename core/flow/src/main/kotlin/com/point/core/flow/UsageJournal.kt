package com.point.core.flow

enum class UsageEventType { SHARED, ACTION, COMPLETED, FAILED, EDGE }

data class UsageEvent(val type: UsageEventType, val detail: String = "")

fun edgeDetail(fromKind: String, capabilityId: String, toKind: String): String =
    "$fromKind>$capabilityId>$toKind"

data class UsageSummary(
    val objects: Int,
    val actions: Int,
    val completed: Int,
    val failed: Int = 0,
) {

    val actionsPerObject: Double get() = if (objects == 0) 0.0 else actions.toDouble() / objects
}

interface UsageJournal {
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun record(event: UsageEvent)
    suspend fun summary(): UsageSummary

    suspend fun graph(): Map<String, Int>
    suspend fun clear()
}
