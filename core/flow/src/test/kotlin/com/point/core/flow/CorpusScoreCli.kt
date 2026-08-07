package com.point.core.flow

import java.io.File

fun main(args: Array<String>) {
    val run = args.getOrNull(0).orEmpty()
    val frames = args.getOrNull(1).orEmpty()
    val report = args.getOrNull(2).orEmpty()
    if (run.isEmpty() || frames.isEmpty()) {
        System.err.println("нужно: -Prun=<каталог прогона> -Pframes=<карта кадров> [-Preport=<отчёт.md>]")
        kotlin.system.exitProcess(2)
    }

    val text = runCatching {
        val map = parseFrameMap(File(frames).readText())
        val cases = map.mapNotNull { (frame, expectation) ->
            val journal = File(run, "$frame.flow.json")

            if (journal.isFile) {
                CorpusCase(frame, expectation.action, factsOf(journal.readText()), expectation.outOfCount)
            } else {
                null
            }
        }
        val missing = map.keys.filter { !File(run, "$it.flow.json").isFile }
        renderCorpusScore(scoreCorpus(cases), missing)
    }.getOrElse { "**корпус не посчитан:** ${it.message}\n" }

    if (report.isEmpty()) print(text) else File(report).appendText(text)
}

internal data class FrameExpectation(val action: String, val outOfCount: OutOfCount?)

internal fun parseFrameMap(text: String): Map<String, FrameExpectation> =
    text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t').map(String::trim).filter(String::isNotEmpty)
            require(parts.size >= 2) { "строка «$line» — нужно «кадр<TAB>действие»" }
            parts[0] to FrameExpectation(parts[1], parts.getOrNull(2)?.let(OutOfCount::byWord))
        }
        .toMap()

internal fun factsOf(json: String): Map<String, String> {
    val meta = json.lastIndexOf("\"metadata\"")
    if (meta < 0) return emptyMap()
    val open = json.indexOf('{', meta)
    if (open < 0) return emptyMap()
    var depth = 0
    var end = -1
    for (i in open until json.length) {
        when (json[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) { end = i; break }
            }
        }
    }
    if (end < 0) return emptyMap()
    return PAIR.findAll(json.substring(open + 1, end))
        .associate { m -> unescape(m.groupValues[1]) to unescape(m.groupValues[2]) }
}

private val PAIR = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun unescape(s: String): String =
    s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")

internal fun renderCorpusScore(score: CorpusScore, missing: List<String>): String = buildString {
    appendLine()
    appendLine("#### ваши примеры: где Point справился сам, без правок")
    val share = score.share
    appendLine(
        if (share == null) "- проверять пока нечего: ни для одного примера не описано, что считать успехом"
        else "- **справился с ${score.ready.size} из ${score.scored}** (${(share * 100).toInt()}%)",
    )
    if (score.ready.isNotEmpty()) appendLine("- справился: ${score.ready.joinToString(", ")}")
    if (score.notReady.isNotEmpty()) appendLine("- не справился: ${score.notReady.joinToString(", ")}")
    OutOfCount.entries.forEach { reason ->
        val frames = score.outOfCount(reason)
        if (frames.isNotEmpty()) appendLine("- ${reason.note}: ${frames.joinToString(", ")}")
    }

    if (score.unnamed.isNotEmpty()) {
        appendLine(
            "- **выпали из счёта, причина не названа** — это дыра в карте примеров: " +
                score.unnamed.joinToString(", "),
        )
    }
    if (missing.isNotEmpty()) {
        appendLine("- не проверялись, прогон до них не дошёл: ${missing.joinToString(", ")}")
    }
}
