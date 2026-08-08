package com.point.source

/**
 * «Точное или примерное место» — один вопрос человеку, а не два разрешения:
 * с Android 12 система рисует их единым диалогом с выбором точности, и выданное
 * примерное покрывает невыданное точное. Отказом считается только отказ по обоим.
 */
private val COVERED_BY = mapOf(
    "android.permission.ACCESS_FINE_LOCATION" to "android.permission.ACCESS_COARSE_LOCATION",
)

fun missingPermissions(required: List<String>, granted: Set<String>): List<String> =
    required.filterNot { it in granted || COVERED_BY[it] in granted }

enum class PermissionOutcome {

    GRANTED,

    DENIED,

    BLOCKED,
}

fun permissionOutcome(
    result: Map<String, Boolean>,
    willAskAgain: (String) -> Boolean,
): PermissionOutcome {
    val granted = result.filterValues { it }.keys
    val denied = result.filterValues { !it }.keys.filterNot { COVERED_BY[it] in granted }
    return when {
        denied.isEmpty() -> PermissionOutcome.GRANTED

        denied.any { !willAskAgain(it) } -> PermissionOutcome.BLOCKED
        else -> PermissionOutcome.DENIED
    }
}
