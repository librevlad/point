package com.point.core.flow

import com.point.core.model.Provenance

/**
 * Происхождение значения по метаданным и его подпись человеку (#264).
 *
 * Контракт живёт в [com.point.core.model.Provenance] (там же, где `PointObject`, — стрелка
 * `:core:model ← :core:flow` вниз не разворачивается), а **чтение из метаданных и русские
 * подписи** — здесь, рядом с `META_SOURCE_SUFFIX`. Прямой прецедент — `ReadingMode.kt` (#263):
 * контракт в ядре, `readingModeLabel` рядом с ним.
 *
 * Направление вывода всегда одно: **`<key>.src` — единственный автор, поле `PointObject.provenance`
 * — типизированная проекция**. Обратно не бывает: узел, у которого поле и `.src` разошлись, —
 * это два источника истины, и инвариантный тест на всех трёх энричерах его ловит.
 */

/** Происхождение значения [key] по `<key>.src`; ключа нет — [Provenance.GIVEN] («не читал никто»). */
fun provenanceOf(metadata: Map<String, String>, key: String): Provenance =
    com.point.core.model.provenanceOf(metadata[key + META_SOURCE_SUFFIX])

/**
 * Как назвать происхождение человеку рядом со значением; `null` — **подписывать нечего**.
 *
 * Прецедент молчания — `readingModeLabel(PRINTED) == null`: норма не подписывается, иначе
 * подпись превращается в шум и перестаёт читаться там, где она важна.
 */
fun provenanceLabel(provenance: Provenance): String? = when (provenance) {
    Provenance.OCR -> "прочитано"
    Provenance.RULE -> "выведено правилом"
    Provenance.MODEL -> "прочитано моделью"
    Provenance.HUMAN -> "подтверждено вами"
    Provenance.GIVEN -> null
}

/**
 * Ключ факта, которым является узел графа: у узла он ровно один (`entity.track`,
 * `graph.role.carrier`), всё остальное в его метаданных — аннотации ([isAnnotationKey]).
 * `null` — у объекта нет собственного факта (файл из Share): судить нечего и врать не о чем.
 */
fun factKeyOf(metadata: Map<String, String>): String? =
    metadata.keys.firstOrNull { !isAnnotationKey(it) }

/**
 * Предположение (#261, design v3 §4): улики считались (`<key>.ev` есть) и независимых классов
 * меньше [CONFIRMED_CLASSES].
 *
 * `false`, когда `.ev` нет вовсе, — **не судили, и врать про это нельзя ни в одну сторону**.
 * Одна реализация на всех: карточка готовности (`FieldReading.assumption`) и подпись найденного
 * объекта считают предположение одной и той же функцией, иначе два экрана скажут разное про
 * одно значение.
 */
fun isAssumption(metadata: Map<String, String>, key: String): Boolean {
    val judged = metadata[key + META_EVIDENCE_SUFFIX]?.split(',')?.filter { it.isNotBlank() }
    return judged != null && judged.size < CONFIRMED_CLASSES
}

/**
 * Источники разошлись о чтении [key]: в `<key>.alt` есть чтение, **отличное от значения**
 * (конвенция `.alt` — победитель включён в список, и он один спором не является).
 */
fun isDisputed(metadata: Map<String, String>, key: String): Boolean {
    val value = metadata[key]?.trim()
    return alternativesOf(metadata, key).any { it.trim() != value }
}

/**
 * Стоит ли подписать значение узла словом «возможно» — **вычисляется, а не наследуется от числа**.
 *
 * Ровно два повода (#264): улик меньше двух независимых классов ([isAssumption], #261) либо
 * источники спорят о чтении ([isDisputed], уже видно строкой «или: …»). Всё остальное — не
 * сомнение, а происхождение, и у него своя подпись ([provenanceLabel]).
 *
 * Правило собрано в функцию, а не разложено по вызову в UI: «когда мы говорим «возможно»» —
 * решение продукта, и оно обязано жить в одном месте, иначе второй экран однажды скажет иначе.
 */
fun isDoubtful(metadata: Map<String, String>): Boolean {
    val key = factKeyOf(metadata) ?: return false
    return isAssumption(metadata, key) || isDisputed(metadata, key)
}
