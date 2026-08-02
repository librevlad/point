package com.point.core.flow

import java.io.File

/**
 * Свод нескольких чтений одной таблицы в одну — для опытов над тем, **чем читать** (#346/ICG).
 *
 * Вопрос владельца поставлен так: даёт ли несколько бесплатных исполнителей заметный прирост
 * против одного Gemini на его рукописной ведомости. Ответить на него можно только чужими
 * чтениями, сведёнными **той же** `reconcile`, что стоит в продукте: свод, переписанный в
 * скрипте опыта, мерил бы качество скрипта, а не качество ансамбля.
 *
 * Вход — по файлу на чтение (TSV, строка = строка таблицы), выход — сведённая таблица тем же
 * TSV, пригодная для `scoreTable`. Пометки спорности (`⚠`) остаются в ячейках: их считает та же
 * метрика, что и на устройстве.
 *
 *   ./gradlew :core:flow:reconcileTables -Pinputs=a.tsv,b.tsv,c.tsv -Pout=consensus.tsv
 */
fun main(args: Array<String>) {
    val inputs = args.getOrNull(0).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    val out = args.getOrNull(1).orEmpty()
    if (inputs.isEmpty() || out.isEmpty()) {
        System.err.println("нужно: -Pinputs=<a.tsv,b.tsv,…> -Pout=<свод.tsv>")
        kotlin.system.exitProcess(2)
    }

    val tables = inputs.map { path ->
        File(path).readText().lines()
            .dropLastWhile { it.isEmpty() }
            .map { it.split('\t') }
    }
    val consensus = reconcile(tables)
    File(out).writeText(consensus.rows.joinToString("\n") { it.joinToString("\t") })

    // Число спорных ячеек — на stdout: оно нужно опыту само по себе, а не только внутри отчёта.
    // Спор здесь не провал, а цена второго мнения, и сравнивать конфигурации без него нельзя.
    println("чтений ${tables.size}, строк ${consensus.rows.size}, спорных ячеек ${consensus.candidates.size}")
}
