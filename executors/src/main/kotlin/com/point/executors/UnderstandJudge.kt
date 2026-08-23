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

    /**
     * Чьё какое прочтение (#1176). Судья выбирает по уликам страницы и не знает, чьё прочтение
     * перед ним: у наклейки два отделения, и для него они одинаковы. Сторона это знает — и
     * спор однозначного факта решает она, но только среди годных прочтений.
     */
    belongings: Map<String, List<PartyReading>> = emptyMap(),
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

        // Значение однозначного факта — прочтение стороны, которой документ адресован
        // (#1176). Место при отправителе — «откуда», место при получателе — «куда»: судье
        // они одинаковы, побеждало первое, то есть склад отправления, и маршрут вёл не туда,
        // куда едет посылка (#772). Сторона выбирает из тех же прошедших воронку прочтений:
        // слово страницы вместо слова модели, форма и контрольная цифра уже спрошены выше
        // (#809), и забракованное сюда не доходит. Прежний выбор остаётся среди прочтений.
        val addressed = chosenByAddressee(key, belongings[key].orEmpty())?.reading
        val winner = addressed?.let { chosen -> scored.firstOrNull { sameReading(it.first, chosen) } }
            ?: scored.maxByOrNull { it.third.size }!!
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
 * То же прочтение страницы после воронки (#1176).
 *
 * Заземление меняет текст кандидата — слово модели уступает слову страницы, — но метки слов
 * у него те же; по ним прочтение стороны и узнаётся среди годных. Прочтение без меток
 * страницы сторону иметь не может.
 */
private fun sameReading(candidate: FieldCandidate, reading: FieldCandidate): Boolean =
    candidate.ids.isNotEmpty() && candidate.ids.map(::bareIndexId) == reading.ids.map(::bareIndexId)

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
