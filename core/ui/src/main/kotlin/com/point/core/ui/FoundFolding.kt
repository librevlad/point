package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/**
 * Однородная сотня находок сворачивается в строку-класс (#1058, решение владельца).
 *
 * Длинный скриншот приносил 119 узлов «Почта», и они печатались подряд: действия начинались
 * после шести прокруток. Знание не урезается — меняется представление: однородное одного
 * вида становится одной строкой с числом («Почта · 119 ›»), внутрь — по тапу. Малые наборы
 * (визитка: телефон, почта, адрес) остаются развёрнутыми как есть.
 */
sealed interface FoundRow {

    /** Обычная находка — своей строкой, как всегда. */
    data class Single(val obj: PointObject) : FoundRow

    /** Класс однородного: вид и все его узлы, свёрнутые в одну строку. */
    data class Group(val kind: ObjectKind, val items: List<PointObject>) : FoundRow
}

/**
 * Со скольких узлов ОДНОГО вида список перестаёт быть читаемым и становится стеной.
 *
 * Пять строк одного вида ещё охватываются взглядом; дальше человек уже не читает их,
 * а прокручивает.
 */
const val FOUND_FOLD_THRESHOLD = 5

fun foldFound(found: List<PointObject>): List<FoundRow> {
    if (found.size <= FOUND_FOLD_THRESHOLD) return found.map { FoundRow.Single(it) }
    val byKind = found.groupBy { it.state.kind }
    val folded = byKind.filterValues { it.size > FOUND_FOLD_THRESHOLD }.keys
    if (folded.isEmpty()) return found.map { FoundRow.Single(it) }

    // Порядок появления сохраняется: класс встаёт на место своего первого узла.
    val seen = mutableSetOf<ObjectKind>()
    return buildList {
        found.forEach { obj ->
            val kind = obj.state.kind
            if (kind !in folded) {
                add(FoundRow.Single(obj))
            } else if (seen.add(kind)) {
                add(FoundRow.Group(kind, byKind.getValue(kind)))
            }
        }
    }
}
