package com.point.core.model

sealed interface ActionYield {

    data object None : ActionYield

    data object Copied : ActionYield

    /**
     * Объект остаётся тем же, к нему прирастает знание.
     *
     * `note` — слова самой способности (#734). Раньше подпись жила у типа исхода, и одна
     * фраза, написанная для «Понять», доставалась каждому, кто объявлял этот исход:
     * «Исправить ошибки» обещало «найдёт суть, суммы, даты и контакты», а правило опечатки.
     */
    data class Same(val note: String? = null) : ActionYield

    data class New(val kind: ObjectKind, val noun: String? = null) : ActionYield

    data object Unknown : ActionYield
}
