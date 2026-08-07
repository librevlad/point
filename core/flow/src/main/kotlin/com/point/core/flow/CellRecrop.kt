package com.point.core.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class RecropQuestion(

    val cell: Pair<Int, Int>,

    val region: Box,

    val readings: List<String>,
)

const val MAX_RECROP_CELLS = 12

const val RECROP_TIMEOUT_MS = 30_000L

private const val MAX_RECROP_READING = 80

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
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (reply != null) answers[question.cell] = reply
            }
        }
    }
    return applyRecrops(voted, answers)
}

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

private fun usableRecropReading(raw: String): String? {
    val lines = raw.trim().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
    val single = lines.singleOrNull() ?: return null
    if (single.length > MAX_RECROP_READING) return null
    if (single.replace("⚠", "").replace("~~", "").isBlank()) return null
    return single
}

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
