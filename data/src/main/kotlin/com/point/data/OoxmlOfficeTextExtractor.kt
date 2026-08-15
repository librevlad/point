package com.point.data

import com.point.core.flow.OfficeTextExtractor
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * #997: из таблицы выходило пусто, а отказ винил старый формат.
 *
 * Текст книги брался только из общей таблицы строк `xl/sharedStrings.xml`: её пишут не все
 * генераторы, а числа не лежат в ней никогда. Смета без общей таблицы строк выглядела пустой,
 * и даже с ней из неё нельзя было достать ни одной суммы — ровно того, ради чего таблицу и
 * открывают. Поэтому таблицу читаем по листам и ячейкам, а путь документа и презентации
 * остаётся прежним.
 */
class OoxmlOfficeTextExtractor @Inject constructor() : OfficeTextExtractor {
    private val delegate = com.point.core.flow.OoxmlOfficeTextExtractor()
    private val sheets = OoxmlSpreadsheetReader()

    override suspend fun extractText(obj: PointObject): String {
        val book = sheets.readSheets(obj)
        val table = book.joinToString(SHEET_SEPARATOR) { sheet -> sheet.joinToString(ROW_SEPARATOR, transform = ::renderRow) }
            .trim()
        return if (table.isNotEmpty()) table else delegate.extractText(obj)
    }

    private fun renderRow(row: List<String>): String = row.joinToString(CELL_SEPARATOR).trimEnd()

    private companion object {
        const val CELL_SEPARATOR = "\t"
        const val ROW_SEPARATOR = "\n"
        const val SHEET_SEPARATOR = "\n\n"
    }
}
