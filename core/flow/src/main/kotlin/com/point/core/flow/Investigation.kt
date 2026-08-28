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

private fun stateOfWire(wire: String?): InvestigationState =
    InvestigationState.entries.firstOrNull { it.wire == wire } ?: InvestigationState.NOT_INVESTIGATED

fun investigationStateOf(
    metadata: Map<String, String>,
    capabilityId: CapabilityId,
    focus: Focus? = null,
): InvestigationState = stateOfWire(metadata[investigationKey(capabilityId, focus)])

/**
 * Область спрашивали — и ни на один вопрос под ней ничего не нашлось (#1000).
 *
 * Это ответ на вопрос человека «что здесь», а не сбой: «не нашлось» — знание (Конституция
 * §13). Пока под областью нет ни одного состояния, ответа нет — не смотрели
 * (`not investigated` ≠ `not found`). Пока хоть один вопрос нашёл, спорит или посмотрел
 * недостаточно — сказать «ничего» нельзя: что-то под областью есть.
 *
 * Считаются только вопросы этой области: у глобальных ключей контекста нет, и ответ про
 * объект за ответ про область не выдаётся.
 */
fun nothingFoundIn(metadata: Map<String, String>, focus: Focus): Boolean {
    val scope = focusScope(focus) ?: return false
    val answers = metadata
        .filterKeys { isStateKey(it) && it.substringAfter('@', missingDelimiterValue = "") == scope }
        .values
        .map(::stateOfWire)
    return answers.isNotEmpty() && answers.all { it == InvestigationState.NOT_FOUND }
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
/**
 * След работы, а не знание об объекте (#1067).
 *
 * Каким шрифтом читали, во сколько раз увеличивали кадр, сколько символов прошли, куда
 * сложены координаты слов — это про ход исследования. Прежде такой след один засчитывался за
 * находку: у тёмного снимка, где не прочиталось ни буквы, оставался `reading.mode`, и вопрос
 * закрывался как «найдено» при пустых руках.
 */
private fun isProcessNote(key: String): Boolean = key in PROCESS_NOTES

private val PROCESS_NOTES: Set<String> = setOf(
    META_READING_MODE, META_READ_UPSCALE, META_READING_DOUBT, META_READ_CHARS, META_READ_TOTAL_CHARS,

    // Ссылка на слой слов — тот же след, а не прочтение (#1270). Плохо прочитанный кадр
    // отдаёт координаты слов уликой, чтобы «Найти» умела встать на строку; прочитано при
    // этом не было ничего. Пока ссылка считалась находкой, вопрос «что написано на снимке»
    // закрывался «найдено» при пустых руках — ровно то, от чего уже спасли `reading.mode`.
    META_OCR_ATOMS_REF,
)

fun investigationOutcome(
    metadata: Map<String, String>,
    factKeys: Collection<String>,
): InvestigationState {
    val told = factKeys.filterNot { isAnnotationKey(it) || isStateKey(it) || isProcessNote(it) || it == META_UNUSABLE_REASON }
    return when {

        // Негодность объекта — ответ про сам объект, а не про заданный вопрос (#988, #1067).
        // Ридер не смог открыть файл: спросить «есть ли здесь текст» не вышло вовсе, и
        // закрывать вопрос «найдено» нельзя. Не «смотрели — не нашлось»: не смотрели.
        told.isEmpty() && META_UNUSABLE_REASON in factKeys ->
            InvestigationState.INSUFFICIENTLY_INVESTIGATED

        // Отброшенное прочтение — след, а не пустые руки (#1032): что-то прочиталось, но
        // проверку не прошло — S10 с несошедшейся контрольной, роль со словом из смешанных
        // алфавитов. Вопрос смотрели, ответа не приняли: это «исследовано недостаточно», и
        // «не нашлось» ответом считаться не может — иначе вопрос закрылся бы и больше не
        // задавался (Конституция: `not investigated` ≠ `not found`).
        //
        // Считается след только этого вопроса: [factKeys] — то, что заявило о себе само
        // исследование, а чужой `.blocked` из накопленного состояния сюда не попадает и
        // ответа на этот вопрос не держит.
        told.isEmpty() && factKeys.any { it.endsWith(META_BLOCKED_SUFFIX) && !metadata[it].isNullOrBlank() } ->
            InvestigationState.INSUFFICIENTLY_INVESTIGATED
        told.isEmpty() -> InvestigationState.NOT_FOUND
        told.any { isDisputed(metadata, it) } -> InvestigationState.CONTRADICTORY
        told.any { isAssumption(metadata, it) } -> InvestigationState.INSUFFICIENTLY_INVESTIGATED
        else -> InvestigationState.FOUND
    }
}
