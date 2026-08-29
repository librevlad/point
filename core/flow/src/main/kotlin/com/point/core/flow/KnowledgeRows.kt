package com.point.core.flow

import com.point.core.model.CapabilityId
import com.point.core.model.Provenance

/**
 * Строки знания объекта для показа человеку — общая модель поверхностей.
 *
 * Всё знание, без лимита: спор («или:») и «ещё»-значения не прячутся (P8),
 * слово человека помечено. Ключи-аннотации и служебные состояния строками не становятся.
 */
data class KnowledgeRow(
    val key: String,
    val name: String,
    val value: String,

    /** Другие прочтения этого же значения — спор, обязан быть виден. */
    val disputed: List<String> = emptyList(),

    /** Другие значения того же вида — не спор, а ещё объекты. */
    val more: List<String> = emptyList(),

    /** Подтверждено человеком. */
    val confirmed: Boolean = false,

    /** Строка документа вокруг значения — подпись при нём, а не оно само (#782). */
    val said: String? = null,
)

/**
 * Как значение читается человеком (#932).
 *
 * Вложенные смысловые ключи — валюта суммы, страна и вид номера — часть значения, а не
 * аннотация: сумма без валюты не сумма. Но приклеивать их сырыми нельзя: рядом с номером
 * вставал код страны для машины — «06 1 ) 2 80-44-2 1 UA городской». Своя страна не
 * называется: человек и так знает, где живёт. Чужая называется словом, а не кодом.
 *
 * Разобранный вид, страна и вид номера принадлежат стране, а страну называет документ
 * (#1029). Номеру, чью страну документ не назвал, достаётся ровно то, что прочитано: тот же
 * `06 1 ) 2 80-44-2 1` выходит на экран покорёженным и без страны и вида рядом. Принятая
 * цена решения владельца 21.08.2026, заведена карточкой #1294: вернуть здесь группировку
 * можно только догадкой о стране, а догадка и есть болезнь #1029.
 */
fun shownKnowledge(
    key: String,
    value: String,
    metadata: Map<String, String> = emptyMap(),
    region: String = PhoneNumbers.DEFAULT_REGION,
): String {
    if (key == META_ENTITY_PHONE) return shownPhone(value, metadata["$key.kind"], region)

    val extras = metadata
        .filterKeys { it.startsWith("$key.") && !isAnnotationKey(it) }
        .values.filter { it.isNotBlank() }
    return (listOf(value) + extras).joinToString(" ")
}

private fun shownPhone(value: String, kind: String?, region: String): String {
    val shown = PhoneNumbers.shown(value, region)
    val abroad = PhoneNumbers.country(value)?.takeIf { it != region }
        ?.let { PhoneNumbers.countryName(it) }
    return listOfNotNull(shown, abroad, kind).joinToString(" · ")
}

data class OpenQuestion(val name: String, val state: InvestigationState)

fun knowledgeRows(
    metadata: Map<String, String>,
    region: String = PhoneNumbers.DEFAULT_REGION,
): List<KnowledgeRow> =
    metadata.keys
        .filter { it.startsWith(META_ENTITY_PREFIX) && !isAnnotationKey(it) }
        .filter { it.removePrefix(META_ENTITY_PREFIX).none { c -> c == '.' } }
        .mapNotNull { key ->
            val name = understoodName(key) ?: return@mapNotNull null
            val value = metadata[key].orEmpty()
            if (value.isBlank()) return@mapNotNull null

            KnowledgeRow(
                key = key,
                name = name,
                value = shownKnowledge(key, value, metadata, region),
                disputed = alternativesOf(metadata, key),
                more = moreOf(metadata, key),
                confirmed = provenanceOf(metadata, key) == Provenance.HUMAN,
                said = metadata[key + META_LINE_SUFFIX]?.takeIf { it.isNotBlank() && it != value },
            )
        }

/**
 * Открытые вопросы знания: «смотрели — не нашли» отличимо от «не смотрели»
 * (Конституция §13). Отвеченные (`found`) вопросы не показываются — есть сами факты.
 * Вопросы под Focus (`@область`) — контекст области, не объекта: пропускаются.
 * Вопрос без человеческого имени не показывается — сырой id не выходит на экран (P2).
 */
fun openQuestions(
    metadata: Map<String, String>,
    nameOf: (CapabilityId) -> String?,
): List<OpenQuestion> =
    metadata.keys
        .mapNotNull(::capabilityOfStateKey)
        .mapNotNull { id ->
            val state = investigationStateOf(metadata, id)
            if (state == InvestigationState.FOUND || state == InvestigationState.NOT_INVESTIGATED) {
                return@mapNotNull null
            }

            // «Не нашлось» не показывается — человек не просил (#1016, решение владельца
            // дословно: «не нашлось не надо показывать - я не просил»). Знание остаётся в
            // графе и отличимо от «не смотрели» для машинных решений; наружу выходят только
            // спор и «посмотрели недостаточно» — им человек обязан не доверять молча (P8).
            if (state == InvestigationState.NOT_FOUND) return@mapNotNull null
            val name = nameOf(id) ?: return@mapNotNull null
            OpenQuestion(name, state)
        }

fun openQuestionLabel(state: InvestigationState): String = when (state) {
    InvestigationState.NOT_FOUND -> "смотрели — не нашлось"
    InvestigationState.INSUFFICIENTLY_INVESTIGATED -> "посмотрели, но недостаточно"
    InvestigationState.CONTRADICTORY -> "прочтения спорят"
    InvestigationState.FOUND, InvestigationState.NOT_INVESTIGATED -> ""
}
