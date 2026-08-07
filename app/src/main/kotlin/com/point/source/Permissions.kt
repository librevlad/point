package com.point.source

fun missingPermissions(required: List<String>, granted: Set<String>): List<String> =
    required.filterNot { it in granted }

enum class PermissionOutcome {

    GRANTED,

    DENIED,

    BLOCKED,
}

fun permissionOutcome(
    result: Map<String, Boolean>,
    willAskAgain: (String) -> Boolean,
): PermissionOutcome {
    val denied = result.filterValues { !it }.keys
    return when {
        denied.isEmpty() -> PermissionOutcome.GRANTED

        denied.any { !willAskAgain(it) } -> PermissionOutcome.BLOCKED
        else -> PermissionOutcome.DENIED
    }
}
