package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/*
 * Однородные находки не заливают экран (#1015).
 *
 * Длинный скриншот дал 119 почтовых адресов, и Point выложил их подряд: одинаковый значок,
 * одинаковая подпись «Почта», значения различаются одним символом. До первого действия —
 * пять экранов прокрутки, а «Найти в документе», ради чего длинный скриншот и открывают,
 * лежало ещё ниже. Сто девятнадцать строк — это отчёт извлекателя, а не ответ человеку на
 * вопрос «что мне стоит сделать».
 *
 * Однородное сворачивается в одну строку с числом и раскрывается по тапу. Знание не
 * пропадает и не режется: в свёртке лежит всё найденное, и число называет его честно.
 * Малое не сворачивается — три почты читаются быстрее любой свёртки.
 */

/** С какого размера однородная стопка перестаёт быть списком и становится строкой. */
const val FOUND_FOLD_FROM = 4

/**
 * Сколько строк найденного вообще может стоять между объектом и действиями.
 *
 * Свёртка по видам сама по себе этого не гарантирует: десять разных видов по три значения —
 * снова экран строк. Пока строк больше предела, сворачивается самая широкая стопка.
 */
const val FOUND_ROWS_MAX = 8

/**
 * Стопка однородного найденного: один вид, все его значения и решение — свёрнута ли она.
 * Свёрнутая занимает одну строку, развёрнутая — по строке на значение.
 */
data class FoundGroup(
    val kind: ObjectKind,
    val items: List<PointObject>,
    val folded: Boolean,
)

/** Сколько строк займёт найденное на экране, если человек ничего не раскрывал. */
fun foundRowCount(groups: List<FoundGroup>): Int =
    groups.sumOf { if (it.folded) 1 else it.items.size }

fun foldFound(found: List<PointObject>): List<FoundGroup> {
    val groups = found.groupBy { it.state.kind }
        .map { (kind, items) -> FoundGroup(kind, items, folded = items.size >= FOUND_FOLD_FROM) }
        .toMutableList()

    // Единичное не сворачивается никогда: «Почта · 1» — свёртка, которая ничего не свернула.
    while (foundRowCount(groups) > FOUND_ROWS_MAX) {
        val widest = groups.withIndex()
            .filter { (_, group) -> !group.folded && group.items.size > 1 }
            .maxByOrNull { (_, group) -> group.items.size }
            ?: break
        groups[widest.index] = widest.value.copy(folded = true)
    }
    return groups
}

/** Подпись свёрнутой стопки: вид и сколько его нашлось. */
fun foundGroupLabel(kind: ObjectKind, count: Int): String = kindLabel(kind) + " · " + grouped(count)

const val FOUND_GROUP_OPEN = "Показать"

const val FOUND_GROUP_CLOSE = "Свернуть"
