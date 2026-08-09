package com.point.core.flow

import com.point.core.model.PointObject

fun continuesObject(before: PointObject, after: PointObject): Boolean = before.uri == after.uri

/**
 * Ключи-ссылки на пересчитываемые слои (OCR): не прочтения, всегда обновляются свежим
 * значением. Общая конвенция знания — одна для всех поверхностей.
 */
val REFRESHABLE_KNOWLEDGE: Set<String> = setOf(META_OCR_TEXT_REF, META_OCR_ATOMS_REF)

fun carryKnowledge(known: PointObject, produced: PointObject): PointObject = produced.copy(
    id = known.id,
    state = known.state.features.fold(produced.state) { state, feature -> state.with(feature) },
    metadata = mergeKnowledge(known.metadata, produced.metadata),
    provenance = maxOf(known.provenance, produced.provenance),
    sourceObjects = produced.sourceObjects.ifEmpty { known.sourceObjects },
    creatorAction = produced.creatorAction ?: known.creatorAction,
)

/**
 * Единственная семантика слияния знания: ей обязаны пользоваться все пути, приносящие знание
 * об объекте — исследование, действие, результат с другого устройства.
 *
 * Новое значение не выбрасывается и не побеждает молча: расхождение остаётся в `.alt`
 * (ADR-0001 §15, RFC §19). Provenance и Evidence не теряются.
 *
 * [refreshable] — ключи, которые не являются прочтениями и всегда обновляются свежим значением
 * (ссылки на пересчитанные слои вроде OCR).
 */
fun mergeKnowledge(
    known: Map<String, String>,
    fresh: Map<String, String>,
    refreshable: Set<String> = emptySet(),
): Map<String, String> {
    if (fresh.isEmpty()) return known

    val readings = fresh.filterKeys { key ->
        key !in refreshable && !isAnnotationKey(key) && !isStateKey(key) && !repeatsKnown(known, key, fresh)
    }

    // Человек — высший источник (ADR-0001 §8), и его слово играет по другим правилам:
    // явное исправление РАЗРЕШАЕТ спор (RFC §19), а машинное чтение не смеет ни вытеснить,
    // ни «отремонтировать» подтверждённое человеком значение.
    val humanFresh = readings.filterKeys { humanSaid(fresh, it) }
    val ontoHuman = readings.filterKeys { it !in humanFresh && humanSaid(known, it) }
    val machine = readings - humanFresh.keys - ontoHuman.keys

    val merged = LinkedHashMap(mergeFacts(known, machine))

    // Машинное чтение поверх человеческого слова: остаётся историей, primary не трогается.
    ontoHuman.forEach { (key, value) ->
        val kept = (alternativesOf(merged, key) + value)
            .distinct()
            .filter { normConsensus(it) != normConsensus(merged[key].orEmpty()) }
        if (kept.isNotEmpty()) merged[key + META_ALT_SUFFIX] = altValue(kept)
    }

    // Человеческое слово: становится primary, прежнее значение — в историю, спора нет.
    humanFresh.forEach { (key, value) ->
        val was = merged[key]
        val kept = (alternativesOf(merged, key) + listOfNotNull(was))
            .distinct()
            .filter { normConsensus(it) != normConsensus(value) }
        if (kept.isEmpty()) merged.remove(key + META_ALT_SUFFIX) else merged[key + META_ALT_SUFFIX] = altValue(kept)
        merged[key] = value
    }

    fresh.forEach { (key, value) ->
        when {
            key in refreshable -> merged[key] = value

            isStateKey(key) -> merged[key] = value

            isAnnotationKey(key) -> mergeAnnotation(merged, key, value)
        }
    }
    return merged
}

private fun humanSaid(metadata: Map<String, String>, key: String): Boolean =
    com.point.core.model.provenanceOf(metadata[key + META_SOURCE_SUFFIX]) ==
        com.point.core.model.Provenance.HUMAN

private fun repeatsKnown(known: Map<String, String>, key: String, fresh: Map<String, String>): Boolean {
    val was = known[key] ?: return false
    return normConsensus(was) == normConsensus(fresh.getValue(key))
}

private fun mergeAnnotation(target: MutableMap<String, String>, key: String, value: String) {
    val existing = target[key]
    if (existing == null) {
        target[key] = value
        return
    }
    when {

        key.endsWith(META_ALT_SUFFIX) || key.endsWith(META_MORE_SUFFIX) || key.endsWith(META_BLOCKED_SUFFIX) ->
            target[key] = altValue((altLines(existing) + altLines(value)).distinct())

        key.endsWith(META_SOURCE_SUFFIX) ->
            target[key] = maxOf(
                com.point.core.model.provenanceOf(existing),
                com.point.core.model.provenanceOf(value),
            ).wire

        key.endsWith(META_EVIDENCE_SUFFIX) -> {
            val was = evidenceClasses(existing)
            val now = evidenceClasses(value)
            if (now.size > was.size) target[key] = value
        }
    }
}

private fun evidenceClasses(value: String): List<String> =
    value.split(',').map(String::trim).filter { it.isNotBlank() }
