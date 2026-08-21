package com.point.core.flow

import com.point.core.model.PointObject

fun continuesObject(before: PointObject, after: PointObject): Boolean = before.uri == after.uri

/**
 * Ключи-ссылки на пересчитываемые слои (OCR) и курсор чтения «Понять»: не прочтения,
 * всегда обновляются свежим значением. Общая конвенция знания — одна для всех поверхностей.
 */
val REFRESHABLE_KNOWLEDGE: Set<String> = setOf(
    META_OCR_TEXT_REF, META_OCR_ATOMS_REF, META_READ_CHARS, META_READ_TOTAL_CHARS,

    // Порядок страниц набора — расположение, заданное человеком, а не прочтение (#1207):
    // новая перестановка заменяет прежнюю, а не уходит с ней в спор.
    META_COLLECTION_ORDER,
)

fun carryKnowledge(
    known: PointObject,
    produced: PointObject,
    region: String = PhoneNumbers.DEFAULT_REGION,
): PointObject = produced.copy(
    id = known.id,
    state = known.state.features.fold(produced.state) { state, feature -> state.with(feature) },
    metadata = mergeKnowledge(known.metadata, produced.metadata, region = region),
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
    region: String = PhoneNumbers.DEFAULT_REGION,
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

    // Спор, уже разрешённый самим патчем, заново не открывается (#1052).
    //
    // «Исправить сильнее» сверяет знание по просьбе человека и возвращает исправленное
    // значение вместе с прежним в «или» — то есть прежнее оно видело и решение приняло.
    // Слияние же считало это очередным чтением, оставляло главным прежнее и прятало
    // исправленное в «ещё»: Point отчитывался «Исправлено: 1», а на экране стояло старое.
    val decided = readings.filterKeys {
        it !in humanFresh && it !in ontoHuman && resolvesKnown(known, fresh, it)
    }
    val machine = readings - humanFresh.keys - ontoHuman.keys - decided.keys

    val merged = LinkedHashMap(mergeFacts(known, machine, region))

    // Машинное чтение поверх человеческого слова: остаётся историей, primary не трогается.
    ontoHuman.forEach { (key, value) ->
        val kept = (alternativesOf(merged, key) + value)
            .distinct()
            .filter { normConsensus(it) != normConsensus(merged[key].orEmpty()) }
        if (kept.isNotEmpty()) merged[key + META_ALT_SUFFIX] = altValue(kept)
    }

    // Разрешённый спор: исправленное становится primary, прежнее уходит в «или» тем же
    // путём, каким это делает слово человека.
    decided.forEach { (key, value) ->
        val kept = (alternativesOf(merged, key) + listOfNotNull(merged[key]))
            .distinct()
            .filter { normConsensus(it) != normConsensus(value) }
        if (kept.isEmpty()) merged.remove(key + META_ALT_SUFFIX) else merged[key + META_ALT_SUFFIX] = altValue(kept)
        merged[key] = value
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

            isStateKey(key) -> merged[key] = keptState(merged[key], value)

            isAnnotationKey(key) -> mergeAnnotation(merged, key, value)
        }
    }
    return withPhoneKnowledge(merged, region)
}

/**
 * Что библиотека знает о номере сверх самого номера (#801).
 *
 * Раньше Point не знал про телефон ничего, кроме цифр. Теперь у номера видно страну и вид —
 * мобильный он или городской. Это знание, а не украшение: по нему видно, что «+48 22…» с
 * украинского документа — польский городской, а не опечатка.
 *
 * Оператор и город библиотека тоже умеет, но их метаданные тяжёлые и после переноса номера
 * между операторами расходятся с правдой. Их здесь нет намеренно.
 */
internal fun withPhoneKnowledge(
    metadata: Map<String, String>,
    region: String = PhoneNumbers.DEFAULT_REGION,
): Map<String, String> {
    val key = META_ENTITY_PHONE
    val value = metadata[key]?.takeIf { it.isNotBlank() } ?: return metadata
    val country = PhoneNumbers.country(value, region) ?: return metadata
    val out = LinkedHashMap(metadata)
    out["$key.country"] = country
    PhoneNumbers.kind(value, region)?.let { out["$key.kind"] = it }
    return out
}

/**
 * Ответ «нашли» не отменяется чужим «не нашли» (ADR-0001 §9, §20).
 *
 * У другого устройства другой набор способностей: компьютер разбирает текст своими
 * правилами и адреса не находит вовсе. Его «смотрели — не нашлось» приезжало поверх
 * найденного телефоном и стирало ответ на вопрос, который давно отвечен. «Не смотрели» и
 * «не нашли» — разные вещи, и ни одна из них не сильнее находки.
 */
private fun keptState(known: String?, fresh: String): String {
    val was = InvestigationState.entries.firstOrNull { it.wire == known }
    val now = InvestigationState.entries.firstOrNull { it.wire == fresh }
    val forgets = now == InvestigationState.NOT_FOUND || now == InvestigationState.NOT_INVESTIGATED
    return if (was == InvestigationState.FOUND && forgets) known!! else fresh
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

        // Одно значение, два исполнителя — не спор, а два пути к одному знанию (#1127).
        // Имена складываются: по ним видно, что вопрос смотрели дважды и ответ сошёлся.
        key.endsWith(META_ACTOR_SUFFIX) ->
            target[key] = actorValue(actorList(existing) + actorList(value))

        key.endsWith(META_EVIDENCE_SUFFIX) -> {
            val was = evidenceClasses(existing)
            val now = evidenceClasses(value)
            if (now.size > was.size) target[key] = value
        }
    }
}

private fun evidenceClasses(value: String): List<String> =
    value.split(',').map(String::trim).filter { it.isNotBlank() }

/**
 * Назвал ли патч прежнее значение своим расхождением (#1052).
 *
 * Это признак решения, а не происхождения: тот, кто кладёт знание, уже видел известное и
 * положил его рядом как «или». Спорить с ним второй раз незачем.
 */
private fun resolvesKnown(known: Map<String, String>, fresh: Map<String, String>, key: String): Boolean {
    val was = known[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val declared = alternativesOf(fresh, key)
    if (declared.isEmpty()) return false
    return declared.any { normConsensus(it) == normConsensus(was) }
}
