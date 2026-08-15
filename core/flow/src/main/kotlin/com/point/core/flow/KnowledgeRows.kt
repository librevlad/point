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
 * аннотация: сумма без валюты не сумма. Но приклеивать их сырыми нельзя. Номер, вычитанный с
 * кадра, приходит покорёженным (`06 1 ) 2 80-44-2 1`), а рядом с ним вставал код страны для
 * машины: «06 1 ) 2 80-44-2 1 UA городской». Библиотека номер разобрала — иначе он не прошёл
 * бы отбор, — значит канонический вид у Point есть.
 *
 * Своя страна не называется: человек и так знает, где живёт. Чужая называется словом, а не
 * кодом.
 */
fun shownKnowledge(key: String, value: String, metadata: Map<String, String> = emptyMap()): String {
    if (key == META_ENTITY_PHONE) return shownPhone(value, metadata["$key.kind"])

    val extras = metadata
        .filterKeys { it.startsWith("$key.") && !isAnnotationKey(it) }
        .values.filter { it.isNotBlank() }
    return (listOf(value) + extras).joinToString(" ")
}

private fun shownPhone(value: String, kind: String?): String {
    val shown = PhoneNumbers.shown(value)
    val abroad = PhoneNumbers.country(value)?.takeIf { it != PhoneNumbers.region }
        ?.let { PhoneNumbers.countryName(it) }
    return listOfNotNull(shown, abroad, kind).joinToString(" · ")
}

data class OpenQuestion(
    val name: String,
    val state: InvestigationState,

    /** Ответ про показанную область, а не про весь объект (#1000). */
    val aboutArea: Boolean = false,
)

fun knowledgeRows(metadata: Map<String, String>): List<KnowledgeRow> =
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
                value = shownKnowledge(key, value, metadata),
                disputed = disputedValues(metadata, key),
                more = moreOf(metadata, key),
                confirmed = provenanceOf(metadata, key) == Provenance.HUMAN,
                said = metadata[key + META_LINE_SUFFIX]?.takeIf { it.isNotBlank() && it != value },
            )
        }

/**
 * Открытые вопросы знания: «смотрели — не нашли» отличимо от «не смотрели»
 * (Конституция §13). Отвеченные (`found`) вопросы не показываются — есть сами факты.
 * Вопросы под Focus (`@область`) — контекст области, не объекта: показываются, только пока эта
 * область и показана, и вытесняют общий ответ по тому же вопросу.
 * Вопрос без человеческого имени не показывается — сырой id не выходит на экран (P2).
 */
fun openQuestions(
    metadata: Map<String, String>,

    /**
     * Область, которую человек показал сейчас (#1000).
     *
     * Вопрос под Focus — другой вопрос: «что в этой области», а не «что в объекте», и вне
     * своей области он не показывается. Но пока область показана, ответ на неё человек ждёт
     * сильнее всего: он сам её и обвёл. Раньше ответ был только в графе — экран после фокуса
     * выглядел ровно так же, как до него.
     */
    scope: String? = null,
    nameOf: (CapabilityId) -> String?,
): List<OpenQuestion> {
    val mine = metadata.keys.filter { key ->
        key.startsWith(META_INVESTIGATED_PREFIX) &&
            ('@' !in key || (scope != null && key.endsWith("@$scope")))
    }

    // Ответ про показанную область важнее общего: человек спрашивал именно про неё.
    val aboutArea = mine.filter { '@' in it }
        .mapTo(mutableSetOf()) { it.removePrefix(META_INVESTIGATED_PREFIX).substringBefore('@') }

    return mine
        .filterNot { '@' !in it && it.removePrefix(META_INVESTIGATED_PREFIX) in aboutArea }
        .mapNotNull { key ->
            val id = CapabilityId(key.removePrefix(META_INVESTIGATED_PREFIX).substringBefore('@'))
            val state = InvestigationState.entries.firstOrNull { it.wire == metadata[key] }
                ?: InvestigationState.NOT_INVESTIGATED
            if (state == InvestigationState.FOUND || state == InvestigationState.NOT_INVESTIGATED) {
                return@mapNotNull null
            }
            val name = nameOf(id) ?: return@mapNotNull null
            OpenQuestion(name, state, aboutArea = '@' in key)
        }
}

fun openQuestionLabel(state: InvestigationState): String = when (state) {
    InvestigationState.NOT_FOUND -> "смотрели — не нашлось"
    InvestigationState.INSUFFICIENTLY_INVESTIGATED -> "посмотрели, но недостаточно"
    InvestigationState.CONTRADICTORY -> "прочтения спорят"
    InvestigationState.FOUND, InvestigationState.NOT_INVESTIGATED -> ""
}
