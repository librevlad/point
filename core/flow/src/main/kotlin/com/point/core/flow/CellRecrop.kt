package com.point.core.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Перечит спорной ячейки кропом (#346, идея владельца): «не отправляем заново весь документ;
 * спорной ячейке отдаётся кроп у сильного маршрута».
 *
 * После свода чтений ([reconcile]) спор о ячейке доставался человеку целиком — пометкой и
 * дропдауном в файле. Третий голос дешевле его внимания: из исходного кадра вырезается строка
 * спорной ячейки, и зрячая модель отвечает на один вопрос — «что написано в этой ячейке?».
 * Ответ входит в то же голосование ([agree]) **ещё одним чтением, а не заменой**: судьбу ячейки
 * решает большинство, а не тот, кто спросил последним.
 *
 * Почему согласие перечита с одним из чтений гасит спор, хотя «два пересказа, совпавшие друг с
 * другом, — ещё не страница»: пересказ читает весь документ и волен фантазировать между строк, а
 * перечит смотрит на пиксели одной строки и отвечает про одну ячейку — это свидетель другого
 * рода, ближе к странице, чем к рассказу о ней.
 */
data class RecropQuestion(
    /** Ячейка в сетке свода — тот же ключ, что у [Consensus.candidates]. */
    val cell: Pair<Int, Int>,
    /** Строка ячейки на снимке в координатах сырого кадра — готовый адрес для `CropEvidence`. */
    val region: Box,
    /** Чтения, между которыми ячейка спорит, — контекст вопроса модели. */
    val readings: List<String>,
)

/**
 * Больше этого числа спорных ячеек — перечит не начинается вовсе. Порог — суждение, и он назван
 * вслух: дюжина точечных вопросов укладывается в общий срок и квоту (то же число и по той же
 * причине, что предел кроп-улик [MAX_EVIDENCE_CROPS]); спор на сотнях ячеек (живой прогон
 * ведомости — 387) означает, что разошлись не ячейки, а чтения целиком, и точечные перечиты
 * такую таблицу не спасают — только съедают время и квоту.
 */
const val MAX_RECROP_CELLS = 12

/**
 * Общий срок ВСЕХ перечитов, а не каждого: перечит — довесок к действию, которое уже отработало,
 * и держать человека дольше ради третьего голоса нельзя. Не успевшие остаются спором и
 * дропдауном — ровно тем, чем были бы без перечита.
 */
const val RECROP_TIMEOUT_MS = 30_000L

/** Предел длины пригодного ответа — тот же, что у варианта в дропдауне ([reconcile]): чтение
 *  длиннее — не содержимое ячейки, а рассказ о ней. */
private const val MAX_RECROP_READING = 80

/**
 * Переспрашивает спорные ячейки [voted] по кропу исходного кадра и вливает ответы в голосование.
 *
 * [ask] — единственная дверь наружу (кроп, файл, сеть — за ней): `null` значит «перечит не
 * состоялся», и спор просто остаётся. Перечиты идут **параллельно** с общим сроком [timeoutMs];
 * успевшие голоса засчитываются, не успевшие отменяются — свод не портится и не ждёт. Отказ
 * одного перечита не роняет ни соседей, ни действие; отмена действия человеком проходит насквозь.
 */
suspend fun recropDisputed(
    voted: Consensus,
    layer: AtomLayer,
    timeoutMs: Long = RECROP_TIMEOUT_MS,
    ask: suspend (RecropQuestion) -> String?,
): Consensus {
    val questions = recropQuestions(voted, layer)
    if (questions.isEmpty()) return voted
    reportStage("Переспрашиваю ${questions.size} ${disputedCellsWord(questions.size)}")
    val answers = ConcurrentHashMap<Pair<Int, Int>, String>()
    withTimeoutOrNull(timeoutMs) {
        questions.forEach { question ->
            launch {
                val reply = try {
                    ask(question)
                } catch (cancelled: CancellationException) {
                    throw cancelled // отмена (человеком или сроком) — не отказ маршрута
                } catch (_: Exception) {
                    null // отказ маршрута оставляет спор этой ячейки, не трогая соседей
                }
                if (reply != null) answers[question.cell] = reply
            }
        }
    }
    return applyRecrops(voted, answers)
}

/**
 * Какие спорные ячейки можно переспросить: есть варианты и есть **однозначное** место на кадре.
 *
 * Адрес — тот же механизм, что у кроп-улики в Word (#267): [locate] ищет строку по её
 * содержимому, и двусмысленность для него — отказ, а не «берём первую». Здесь это важнее, чем
 * там: кроп соседней строки человек хотя бы увидит глазами, а модель честно прочитает не ту
 * ячейку — и её голос будет уверенно неверным. Ячейка без адреса не перечитывается.
 *
 * Адрес при этом собирается из **бесспорных** ячеек строки. Спорное значение самому себе не
 * адрес: ровно когда свод ошибся, его строки с этим числом на кадре нет — зато то же число
 * почти всегда есть в чужой строке (количества в ведомости повторяются), и «однозначное» место
 * оказывается чужим. Модель тогда честно читает не ту ячейку, голосом 2 из 3 подтверждает
 * ошибку и снимает пометку — перечит, построенный ловить спор, прячет его от человека.
 * Соседняя спорная ячейка — адрес не лучше: её значение под тем же вопросом.
 */
internal fun recropQuestions(voted: Consensus, layer: AtomLayer): List<RecropQuestion> {
    if (voted.candidates.isEmpty() || voted.candidates.size > MAX_RECROP_CELLS) return emptyList()
    if (layer.atoms.isEmpty()) return emptyList()
    return voted.candidates.mapNotNull { (cell, readings) ->
        if (readings.isEmpty()) return@mapNotNull null
        val row = voted.rows.getOrNull(cell.first) ?: return@mapNotNull null
        val address = row.filterIndexed { c, _ -> (cell.first to c) !in voted.candidates }
        val region = layer.locate(address.joinToString(" ")) ?: return@mapNotNull null
        RecropQuestion(cell, region, readings)
    }
}

/**
 * Вливает ответы перечита в свод. Ответ — голос в [agree] рядом с прежними чтениями ячейки:
 *
 * - строгое большинство собралось — спор решён: ячейка получает победившее чтение чистым,
 *   дропдаун снимается (пометка [validateTable] от этого не зависит и может вернуться);
 * - большинства нет — спор остаётся, а ответ встаёт вариантом в дропдаун: выбросить прочитанное
 *   молча нельзя, но и заменять им голосование — тоже.
 *
 * Голос считается по различимым чтениям: других весов свод не хранит ([Consensus.candidates] —
 * список различимых чтений, а не протокол голосов), и при сегодняшних двух чтениях это точный
 * счёт. Ответ не по контракту — многословный, пустой или голое «⚠» («не разобрал») — голосом
 * не становится: спор просто остаётся, как был.
 */
private fun applyRecrops(voted: Consensus, answers: Map<Pair<Int, Int>, String>): Consensus {
    if (answers.isEmpty()) return voted
    val rows = voted.rows.map { it.toMutableList() }
    val candidates = LinkedHashMap(voted.candidates)
    answers.forEach { (cell, raw) ->
        val readings = candidates[cell] ?: return@forEach
        val reply = usableRecropReading(raw) ?: return@forEach
        val row = rows.getOrNull(cell.first) ?: return@forEach
        if (cell.second !in row.indices) return@forEach
        val all = readings + reply
        val verdict = agree(all) ?: return@forEach
        val top = all.groupBy(::normConsensus).values.maxOf { it.size }
        if (top * 2 > all.size) {
            row[cell.second] = verdict.value
            candidates.remove(cell)
        } else {
            row[cell.second] = if (verdict.value.contains('⚠')) verdict.value else "${verdict.value}⚠"
            candidates[cell] = verdict.candidates.filter { it.length <= MAX_RECROP_READING }
        }
    }
    return Consensus(rows.map { it.toList() }, candidates, voted.sources)
}

/** Ответ перечита, пригодный быть чтением: одна строка не длиннее варианта дропдауна, с
 *  содержимым. Ограждения кода срезаются; «⚠» без содержимого — честное «не разобрал». */
private fun usableRecropReading(raw: String): String? {
    val lines = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
    val single = lines.singleOrNull() ?: return null
    if (single.length > MAX_RECROP_READING) return null
    if (single.replace("⚠", "").replace("~~", "").isBlank()) return null
    return single
}

/** «1 спорную ячейку», «2 спорные ячейки», «12 спорных ячеек» — стадия не должна выглядеть
 *  машинным переводом. */
private fun disputedCellsWord(n: Int): String {
    val tens = n % 100
    val ones = n % 10
    return when {
        tens in 11..14 -> "спорных ячеек"
        ones == 1 -> "спорную ячейку"
        ones in 2..4 -> "спорные ячейки"
        else -> "спорных ячеек"
    }
}
