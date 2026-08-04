package com.point.core.flow

import java.io.File

/**
 * Счётчик таблиц для харнесса корпуса (#262): читает выгруженную таблицу (TSV) и эталон кадра,
 * зовёт [scoreTable] и пишет отчёт в Markdown.
 *
 * Живёт в тестовых исходниках сознательно. У метрики должна быть **одна** реализация — вторая,
 * написанная на awk ради удобства шелла, разошлась бы с первой молча, и харнесс считал бы не то,
 * что считают тесты. При этом `main` в библиотеке, которую тянет приложение, — мусор в артефакте:
 * это инструмент разработки, а не продукт. Отсюда компромисс — код метрики в `main`, её CLI рядом
 * с её же тестами.
 *
 * Зовётся из `tools/table-score.sh` через `./gradlew :core:flow:scoreTable -Ptable=… -Pexpected=…`.
 * Отчёт пишется файлом, а не в stdout, потому что stdout Gradle делит с собственным шумом.
 */
fun main(args: Array<String>) {
    val table = args.getOrNull(0).orEmpty()
    val expected = args.getOrNull(1).orEmpty()
    val report = args.getOrNull(2).orEmpty()
    if (table.isEmpty() || expected.isEmpty()) {
        System.err.println("нужно: -Ptable=<таблица.tsv> -Pexpected=<эталон.tsv> [-Preport=<отчёт.md>]")
        kotlin.system.exitProcess(2)
    }
    val frame = File(expected).name.substringBefore('.')
    val text = runCatching {
        val expectation = parseTableExpectation(frame, File(expected).readText())
        // Пустая строка выбрасывается только в хвосте (перевод строки в конце файла): пустая
        // строка ПОСРЕДИ таблицы — это строка документа, и молча её потерять значило бы
        // подправить счёт строк в свою пользу.
        val rows = File(table).readText().lines()
            .dropLastWhile { it.isEmpty() }
            .map { it.split('\t') }
        // Два блока подряд, а не одна оценка: «не соврал ли файл» и «можно ли им пользоваться» —
        // разные обещания человеку, и среднее между ними не значит ничего (#493).
        renderTableScore(scoreTable(expectation, rows)) + renderUsability(scoreUsable(frame, rows))
    }.getOrElse { "**эталон или таблица не прочитаны:** ${it.message}\n" }

    if (report.isEmpty()) print(text) else File(report).appendText(text)
}

/**
 * Отчёт человеку. Порядок строк — порядок важности: сначала то, что молчит, потом то, что
 * предупреждает. Проценты округлены, сами числа — нет: доля без своих числителя и знаменателя
 * скрывает, на чём она посчитана.
 */
fun renderTableScore(score: TableScore): String = buildString {
    appendLine("#### таблица примера ${score.frame}")
    appendLine(
        "- строк в документе ${score.documentRows}, в файле ${score.tableRows}; " +
            "колонок ${score.documentColumns} против ${score.tableColumns}",
    )
    appendLine(
        "- найдено строк ${score.found.size} из ${score.found.size + score.lost.size}, " +
            "потеряно ${score.lost.size}, лишних ${score.extra}",
    )
    if (score.lost.isNotEmpty()) appendLine("  - потеряны: ${score.lost.joinToString(", ")}")
    appendLine("- проверено ячеек ${score.checkedCells}, совпало ${score.matchedCells}${percent(score.cellShare)}")
    appendLine("- **ошибок без предупреждения ${score.silent.size}**, предупреждений ${score.flagged.size}")
    score.silent.forEach { appendLine("  - без предупреждения: ${diff(it)}") }
    score.flagged.forEach { appendLine("  - предупреждение: ${diff(it)}") }
    appendLine("- ячеек с предупреждением ${score.markedCells} из ${score.totalCells}${percent(score.markedShare)}")
    when {
        score.unjudged -> appendLine(
            "- **проверить нечем:** для этого примера не названо ни одного верного значения — " +
                "сошлись только строки и ширина, а само содержимое ячеек никто не сверял",
        )
        score.passed -> appendLine("- **всё верно**")
        else -> appendLine("- **не годится:** " + score.failures.joinToString("; ") { it.reason })
    }
    appendLine()
}

/**
 * Отчёт о годности (#493): сколько из полученного человек может взять как есть. Значений ячеек
 * здесь нет и быть не может — это документы владельца; печатаются только числа.
 */
fun renderUsability(score: UsabilityScore): String = buildString {
    appendLine("#### годность файла ${score.frame}")
    appendLine(
        "- строк на листе ${score.sheetRows}, из них в непрочитанном ${score.dumpRows}; " +
            "непустых ячеек ${score.cells}",
    )
    appendLine("- в непрочитанном ячеек ${score.dumpCells}${percent(score.dumpShare)}")
    appendLine(
        "- ячеек с символьным шумом ${score.noisyCells} из ${score.documentCells}${percent(score.noiseShare)}, " +
            "с предупреждением ${score.flaggedCells}${percent(score.flaggedShare)}",
    )
    appendLine("- **можно взять как есть ${score.usableCells} из ${score.cells}${percent(score.usableShare)}**")
    if (score.unfit.isEmpty()) {
        appendLine("- названных причин негодности нет")
    } else {
        appendLine("- **негоден:** " + score.unfit.joinToString("; ") { it.reason })
    }
    appendLine()
}

private fun diff(d: CellDiff): String =
    "строка ${d.key}, колонка ${d.column + 1} — верно «${d.expected}», " +
        (d.actual?.let { "получили «$it»" } ?: "ячейки нет")

private fun percent(share: Double?): String =
    share?.let { " (${Math.round(it * 100)}%)" } ?: ""
