package com.point.core.model

/**
 * A pre-execution preview of what a capability will do (#97) — shown before the user commits, so a
 * terminal action (add contact, create event, open map) is predictable and trusted. A realizer
 * returns null to run immediately with no confirm step; non-null pops a small confirm sheet.
 */
data class Preview(
    val title: String,
    val lines: List<String> = emptyList(),
    val confirmLabel: String = "Подтвердить",
)
