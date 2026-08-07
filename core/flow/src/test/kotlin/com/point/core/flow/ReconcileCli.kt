package com.point.core.flow

import java.io.File

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

    println("чтений ${tables.size}, строк ${consensus.rows.size}, спорных ячеек ${consensus.candidates.size}")
}
