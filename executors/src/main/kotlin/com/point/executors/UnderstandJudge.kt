package com.point.executors

import com.point.core.flow.AtomAddress
import com.point.core.flow.AtomLayer
import com.point.core.flow.FieldCandidate
import com.point.core.flow.PartyReading
import com.point.core.flow.bareIndexId
import com.point.core.flow.chosenByAddressee
import com.point.core.flow.fieldEvidence
import com.point.core.flow.formEvidence
import com.point.core.flow.foundLiterally
import com.point.core.flow.isRepairOf
import com.point.core.flow.normConsensus
import com.point.core.flow.resolve
import com.point.core.flow.ruleEvidence
import com.point.core.flow.s10CheckDigitValid
import com.point.core.flow.semanticFits
import com.point.core.flow.standsInReadText

/**
 * Рассудить: что из названного моделью становится знанием (#835).
 *
 * Опора в прочитанном (#809), форма значения (#657), спор кандидатов, происхождение.
 * Чистая логика: ни сети, ни файлов — её можно проверять напрямую.
 */
internal fun judgeFields(
    fields: Map<String, List<FieldCandidate>>,
    layer: AtomLayer?,

    /**
     * Всё, что Point прочитал сам: слой слов снимка или текст самого объекта (#809). Пусто —
     * сверять не с чем, и значение принимается как раньше.
     */
    readText: String = layer?.text.orEmpty(),
): JudgedFields {
    val ruleMarks = layer?.ruleEvidence().orEmpty()
    val won = LinkedHashMap<String, JudgedField>()
    val retry = mutableSetOf<String>()
    val blockedByKey = LinkedHashMap<String, List<String>>()
    fields.forEach { (key, rawCandidates) ->
        val grounded = rawCandidates.flatMap { groundCandidate(key, it, layer, readText) }
            .distinctBy { it.first.text to it.first.ids }
        val (blocked, alive) = grounded.partition { (c, _) -> s10CheckDigitValid(c.text) == false }
        if (blocked.isNotEmpty()) blockedByKey[key] = blocked.map { it.first.text }
        if (alive.isEmpty()) {
            if (grounded.isNotEmpty()) retry += key
            return@forEach
        }
        val scored = alive.map { (c, isGrounded) ->

            val evidence = layer?.fieldEvidence(key, c, ruleMarks) ?: formEvidence(key, c.text)
            Triple(c, isGrounded, evidence)
        }
        val winner = scored.maxByOrNull { it.third.size }!!
        won[key] = JudgedField(
            text = winner.first.text,
            evidence = winner.third,
            grounded = winner.second,
            candidates = scored.map { it.first.text }.distinct(),
            line = winner.first.line,
        )
    }
    return JudgedFields(won, retry, blockedByKey)
}

/**
 * Значение однозначного факта — прочтение стороны, которой документ адресован (#1176).
 *
 * Судья полей выбирает по уликам страницы и не знает, чьё прочтение перед ним: два отделения
 * наклейки для него одинаковы, и побеждало первое — склад отправления, а маршрут вёл не туда,
 * куда едет посылка (#772). Принадлежность приходит позже, из ролей того же ответа модели, и
 * здесь эти два знания встречаются. Прежний выбор не пропадает — он остаётся среди прочтений.
 *
 * Правило общее: оно ничего не знает ни про место, ни про наклейку — только про то, что у
 * однозначного факта прочтения спорят, а сторона этот спор решает.
 *
 * Сторона решает спор годных прочтений и не воскрешает забракованное: номер, не сошедшийся
 * с контрольной цифрой ([blocked]), и значение не той формы значением не становятся, в чьей
 * бы колонке они ни стояли.
 */
internal fun withPartyReadings(
    fields: Map<String, JudgedField>,
    belongings: Map<String, List<PartyReading>>,
    layer: AtomLayer?,
    blocked: Map<String, List<String>> = emptyMap(),
): Map<String, JudgedField> {
    if (layer == null || belongings.isEmpty()) return fields
    val ruleMarks = layer.ruleEvidence()
    return fields.mapValues { (key, judged) ->
        val chosen = chosenByAddressee(key, belongings[key].orEmpty())?.reading ?: return@mapValues judged
        if (normConsensus(chosen.text) == normConsensus(judged.text)) return@mapValues judged
        if (semanticFits(key, chosen.text) == false) return@mapValues judged
        if (blocked[key].orEmpty().any { normConsensus(it) == normConsensus(chosen.text) }) {
            return@mapValues judged
        }
        JudgedField(
            text = chosen.text,
            evidence = layer.fieldEvidence(key, chosen, ruleMarks),
            grounded = true,
            candidates = (listOf(chosen.text) + judged.candidates).distinct(),
        )
    }
}

private fun groundCandidate(
    key: String,
    candidate: FieldCandidate,
    layer: AtomLayer?,
    readText: String,
): List<Pair<FieldCandidate, Boolean>> {
    if (layer == null || candidate.ids.isEmpty()) return withoutMarks(candidate, readText)
    val resolved = layer.resolve(AtomAddress.ByIds(candidate.ids.map(::bareIndexId)))
    if (resolved.atoms.isEmpty()) return withoutMarks(candidate, readText)
    val page = resolved.text
    val model = candidate.text
    return when {
        normConsensus(model) == normConsensus(page) -> listOf(candidate.copy(text = page) to true)
        isRepairOf(page, model) -> listOf(candidate to true)
        semanticFits(key, page) == false && semanticFits(key, model) == true ->
            listOf(FieldCandidate(model) to false)
        else -> listOf(
            candidate.copy(text = page) to true,
            FieldCandidate(model) to false,
        )
    }
}

/**
 * Значение, за которым нет меток слов, — «нет в тексте, нет знания» (#809, решение владельца
 * 12.08.2026).
 *
 * Модель обязана называть метки слов, когда слова значения есть на странице; без меток
 * значение раньше принималось как есть, и выдуманная дата вставала рядом с настоящей. Теперь
 * оно принимается, только если стоит в прочитанном тексте: дословно — тогда это слово
 * страницы, или как починка искажения — тогда это слово модели.
 */
private fun withoutMarks(
    candidate: FieldCandidate,
    readText: String,
): List<Pair<FieldCandidate, Boolean>> {
    val bare = candidate.copy(ids = emptyList())
    return when {

        // Читать нечем — сверять не с чем: снимок без распознанных слов читается глазами,
        // и там всё остаётся как было (#664).
        readText.isBlank() -> listOf(bare to false)

        // Слово страницы — значение прочитано; починка искажения — слово модели (#809).
        foundLiterally(candidate.text, readText) -> listOf(bare to true)
        standsInReadText(candidate.text, readText) -> listOf(bare to false)
        else -> emptyList()
    }
}

/**
 * Сомнение модели становится обычной оговоркой знания (#670): у зрячего чтения нет ни
 * слоя слов, ни судьи, поэтому подтверждающих улик у значения нет — пустой список улик
 * и означает «возможно». Значение при этом остаётся значением: сомнение не отменяет факт.
 */
internal fun doubts(merged: Map<String, String>, unsure: Set<String>): Map<String, String> =
    unsure.filter { merged[it]?.isNotBlank() == true }
        .associate { it + com.point.core.flow.META_EVIDENCE_SUFFIX to "" }
