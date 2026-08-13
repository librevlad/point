package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.RelationType

/**
 * Обратное преобразование того, что Point только что сделал (#925).
 *
 * Текст → «Озвучить» → запись, и на экране записи первым действием стояло «Расшифровать»:
 * Point предлагал платным облачным вызовом получить обратно тот самый текст, из которого
 * минуту назад эту запись и сделал. Круг замыкается: ожидание, деньги квоты и результат хуже
 * исходника — числа словами вернулись цифрами, окончание фразы съелось.
 *
 * Класс, а не случай: снимок, полученный из PDF, и «прочитать слова с кадра»; текст,
 * полученный из таблицы, и «собрать таблицу».
 *
 * Решение владельца 13.08.2026: **«Опустить и сказать „у вас это есть“»**. Действие не
 * прячется — конституция говорит, что Intent меняет порядок, а не список, — оно уходит вниз
 * и несёт вторую строку: исходник уже есть, вот он.
 *
 * Опирается на связь «получено из» (#946): откуда объект взялся, знает граф, а не догадка.
 */
fun inverseSourceKind(graph: GraphState): ObjectKind? {
    val from = graph.relations
        .filter { it.fromId == graph.obj.id && it.type == RelationType.DERIVED_FROM }
        .map { it.toId }
        .toSet()
    if (from.isEmpty()) return null
    return graph.found.firstOrNull { it.id in from }?.state?.kind
}

/** Вернёт ли это действие ровно то, из чего объект и получен. */
fun givesBackTheSource(capability: Capability, graph: GraphState, sourceKind: ObjectKind?): Boolean {
    if (sourceKind == null) return false
    if (capability.meta.investigation) return false
    val next = capability.produces(graph.state) ?: return false
    return next.kind == sourceKind && next.kind != graph.state.kind
}

/** Вторая строка такого действия: исходник никуда не делся. */
fun sourceIsHere(kind: ObjectKind): String = "у вас это уже есть — " + wasCalled(kind)

private fun wasCalled(kind: ObjectKind): String = when (kind) {
    ObjectKind.TEXT -> "исходный текст рядом"
    ObjectKind.IMAGE -> "исходный снимок рядом"
    ObjectKind.PDF -> "исходный документ рядом"
    ObjectKind.AUDIO -> "исходная запись рядом"
    else -> "исходник рядом"
}
