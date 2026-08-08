package com.point.core.flow

import com.point.core.model.CapabilityId

/**
 * Состояние знания для пары `(ObjectId, CapabilityId)` — ADR-0001 §9.
 *
 * Отвечает на один вопрос: исследовался ли этот вопрос для этого объекта.
 * Состоянием операции не является: сорвавшееся исследование оставляет знание нетронутым.
 */
enum class InvestigationState(val wire: String) {

    NOT_INVESTIGATED("not_investigated"),

    FOUND("found"),

    NOT_FOUND("not_found"),

    INSUFFICIENTLY_INVESTIGATED("insufficiently_investigated"),

    CONTRADICTORY("contradictory"),
}

const val META_INVESTIGATED_PREFIX = "investigated."

fun isStateKey(key: String): Boolean = key.startsWith(META_INVESTIGATED_PREFIX)

fun investigationKey(capabilityId: CapabilityId): String = META_INVESTIGATED_PREFIX + capabilityId.value

/**
 * Вопрос под Focus — другой вопрос: «что в этой области», а не «что в объекте».
 *
 * Поэтому его состояние живёт под ключом с контекстом области и никогда не пишется поверх
 * глобального: focused `NOT_FOUND` означает «в этой области не найдено», не больше.
 */
fun investigationKey(capabilityId: CapabilityId, focus: Focus?): String {
    val scope = focus?.let(::focusScope) ?: return investigationKey(capabilityId)
    return investigationKey(capabilityId) + "@" + scope
}

fun focusScope(focus: Focus): String? =
    focus.region?.let(::regionWire) ?: focus.atomIds.takeIf { it.isNotEmpty() }?.joinToString(" ")

fun investigationStateOf(
    metadata: Map<String, String>,
    capabilityId: CapabilityId,
    focus: Focus? = null,
): InvestigationState {
    val wire = metadata[investigationKey(capabilityId, focus)] ?: return InvestigationState.NOT_INVESTIGATED
    return InvestigationState.entries.firstOrNull { it.wire == wire } ?: InvestigationState.NOT_INVESTIGATED
}

fun withInvestigation(
    metadata: Map<String, String>,
    capabilityId: CapabilityId,
    state: InvestigationState,
    focus: Focus? = null,
): Map<String, String> = metadata + (investigationKey(capabilityId, focus) to state.wire)

/**
 * Состояние знания после успешно завершённого исследования.
 *
 * [factKeys] — ключи знания, которые это исследование заявило о себе; [metadata] — состояние
 * объекта уже после merge, потому что расхождение видно только там.
 *
 * Сорвавшееся исследование сюда не попадает: у него нет исхода знания (ADR-0001 §9).
 */
fun investigationOutcome(
    metadata: Map<String, String>,
    factKeys: Collection<String>,
): InvestigationState {
    val told = factKeys.filterNot { isAnnotationKey(it) || isStateKey(it) }
    return when {
        told.isEmpty() -> InvestigationState.NOT_FOUND
        told.any { isDisputed(metadata, it) } -> InvestigationState.CONTRADICTORY
        told.any { isAssumption(metadata, it) } -> InvestigationState.INSUFFICIENTLY_INVESTIGATED
        else -> InvestigationState.FOUND
    }
}
