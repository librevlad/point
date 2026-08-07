package com.point.core.model

sealed interface ActionYield {

    data object None : ActionYield

    data object Copied : ActionYield

    data object Same : ActionYield

    data class New(val kind: ObjectKind, val noun: String? = null) : ActionYield

    data object Unknown : ActionYield
}
