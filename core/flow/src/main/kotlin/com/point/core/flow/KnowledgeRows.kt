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

data class OpenQuestion(val name: String, val state: InvestigationState)

fun knowledgeRows(metadata: Map<String, String>): List<KnowledgeRow> =
    metadata.keys
        .filter { it.startsWith(META_ENTITY_PREFIX) && !isAnnotationKey(it) }
        .filter { it.removePrefix(META_ENTITY_PREFIX).none { c -> c == '.' } }
        .mapNotNull { key ->
            val name = understoodName(key) ?: return@mapNotNull null
            val value = metadata[key].orEmpty()
            if (value.isBlank()) return@mapNotNull null

            // Вложенные смысловые ключи (валюта суммы) — часть значения, не аннотация.
            val extras = metadata
                .filterKeys { it.startsWith("$key.") && !isAnnotationKey(it) }
                .values.filter { it.isNotBlank() }
            KnowledgeRow(
                key = key,
                name = name,
                value = (listOf(value) + extras).joinToString(" "),
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
        .filter { it.startsWith(META_INVESTIGATED_PREFIX) && '@' !in it }
        .mapNotNull { key ->
            val id = CapabilityId(key.removePrefix(META_INVESTIGATED_PREFIX))
            val state = investigationStateOf(metadata, id)
            if (state == InvestigationState.FOUND || state == InvestigationState.NOT_INVESTIGATED) {
                return@mapNotNull null
            }
            val name = nameOf(id) ?: return@mapNotNull null
            OpenQuestion(name, state)
        }

fun openQuestionLabel(state: InvestigationState): String = when (state) {
    InvestigationState.NOT_FOUND -> "смотрели — не нашлось"
    InvestigationState.INSUFFICIENTLY_INVESTIGATED -> "посмотрели, но недостаточно"
    InvestigationState.CONTRADICTORY -> "прочтения спорят"
    InvestigationState.FOUND, InvestigationState.NOT_INVESTIGATED -> ""
}
