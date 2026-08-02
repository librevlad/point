package com.point.core.flow

import java.io.File

/**
 * Счётчик корпуса для харнесса (#262): читает журналы флоу, снятые прогоном с устройства, и
 * карту «кадр → ожидаемое действие», зовёт [scoreCorpus] и пишет отчёт.
 *
 * Зачем он вообще. Прогон `tools/corpus-run.sh` уже снимал с каждого кадра `NN.flow.json` — вход
 * метрики, — но само число собиралось **руками**: человек открывал журналы, сверял их с
 * ожидаемым действием, которое помнил, и писал итог в документ. Пока кадров было два, это
 * работало; на двадцати трёх это перестаёт быть измерением и становится пересказом. Метрика,
 * которую считает человек, меряет прежде всего его терпение.
 *
 * Живёт в тестовых исходниках по той же причине, что и [main] счётчика таблиц: у метрики должна
 * быть одна реализация, и она же под тестами, — но `main` в библиотеке, которую тянет
 * приложение, был бы мусором в артефакте.
 *
 * Зовётся из `tools/corpus-score.sh`:
 *   ./gradlew :core:flow:scoreCorpus -Prun=out -Pframes=tools/corpus/frames.tsv -Preport=out/report.md
 */
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
        val cases = map.mapNotNull { (frame, action) ->
            val journal = File(run, "$frame.flow.json")
            // Кадра без журнала в счёте нет — но и молчать о нём нельзя: прогон мог до него не
            // дойти, и «не мерили» обязано отличаться от «не готово».
            if (journal.isFile) CorpusCase(frame, action, factsOf(journal.readText())) else null
        }
        val missing = map.keys.filter { !File(run, "$it.flow.json").isFile }
        renderCorpusScore(scoreCorpus(cases), missing)
    }.getOrElse { "**корпус не посчитан:** ${it.message}\n" }

    if (report.isEmpty()) print(text) else File(report).appendText(text)
}

/**
 * Карта «кадр → ожидаемое действие»: по строке на кадр, `NN<TAB>действие`, `#` — комментарий.
 *
 * Действие — это [ActionSchema.id] там, где схема есть (`track-parcel`, `route`,
 * `meter-reading`, `save-contact`), и человеческое имя там, где её ещё нет (`извлечь таблицу`).
 * Второе не ошибка формата, а сам смысл счёта: такие кадры уходят в `unscored` и называются
 * поимённо, вместо того чтобы тихо выпасть из знаменателя.
 */
internal fun parseFrameMap(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t').map(String::trim).filter(String::isNotEmpty)
            require(parts.size >= 2) { "строка «$line» — нужно «кадр<TAB>действие»" }
            parts[0] to parts[1]
        }
        .toMap()

/**
 * Факты последнего объекта журнала флоу — вход [ActionSchema.readiness].
 *
 * Берётся ПОСЛЕДНИЙ объект стека: прогон корпуса заканчивается на том объекте, который человек
 * видит, и именно его факты решают, готово ли действие. Разбор — свой, минимальный: `:core:flow`
 * android-free и без библиотек, а JSON здесь всегда свой собственный, записанный тем же
 * приложением.
 */
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

/** Пара «"ключ": "значение"» с экранированием внутри значения. */
private val PAIR = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun unescape(s: String): String =
    s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")

/**
 * Отчёт человеку. Числитель и знаменатель раздельно, неизмеримые — поимённо, а кадры без журнала
 * названы отдельной строкой: «не мерили» и «не готово» — разные факты, и складывать их значит
 * подменять один другим.
 */
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
    if (score.unscored.isNotEmpty()) {
        appendLine("- пока не проверяем, не описано что считать успехом: ${score.unscored.joinToString(", ")}")
    }
    if (missing.isNotEmpty()) {
        appendLine("- не проверялись, прогон до них не дошёл: ${missing.joinToString(", ")}")
    }
}
