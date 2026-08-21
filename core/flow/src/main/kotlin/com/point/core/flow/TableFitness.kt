package com.point.core.flow

const val UNFIT_UNREAD_SHARE: Double = 1.0 / 3.0

private const val MIN_JUDGEABLE_WORDS = 40

private const val MIN_JUDGEABLE_CELLS = 24

fun unfitTable(document: DocumentLayout, disputed: Int, gridCells: Int): String? {
    val read = document.readWords
    val unread = document.unreadWords
    if (read == 0 && unread > 0) {
        return "Прочитать таблицу не удалось: ни одной части документа со страницы не собралось"
    }
    if (unread + read >= MIN_JUDGEABLE_WORDS && unread.toDouble() / (unread + read) >= UNFIT_UNREAD_SHARE) {
        return "Прочитать таблицу не удалось: больше трети страницы разобрать не вышло"
    }
    if (gridCells >= MIN_JUDGEABLE_CELLS && disputed.toDouble() / gridCells >= WARNING_WALL_SHARE) {
        return "Прочитать таблицу не удалось: чтения разошлись больше чем на трети ячеек"
    }
    return null
}

fun survivedHeaderRows(lead: List<List<String>>, consensus: List<List<String>>, headerRows: Int): Int {
    if (headerRows <= 0) return 0
    val n = minOf(headerRows, lead.size, consensus.size)
    if (n < headerRows) return 0
    val same = (0 until n).all { i -> rowKey(lead[i]) == rowKey(consensus[i]) }
    return if (same) headerRows else 0
}

internal fun rowKey(row: List<String>): String =
    row.joinToString("") { normConsensus(it) }
