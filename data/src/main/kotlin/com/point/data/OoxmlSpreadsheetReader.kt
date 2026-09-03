package com.point.data

import com.point.core.flow.SpreadsheetReader
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Разбор живёт в `:core:flow` (#997): ту же таблицу открывает и компьютер, у которого Hilt
 * нет. Здесь остаётся только точка внедрения.
 */
class OoxmlSpreadsheetReader @Inject constructor() : SpreadsheetReader {
    private val delegate = com.point.core.flow.OoxmlSpreadsheetReader()

    override suspend fun readRows(obj: PointObject): List<List<String>> = delegate.readRows(obj)

    // Без этого умолчание интерфейса отдало бы один лист — и телефон снова видел бы только первый (#1417).
    override suspend fun readSheets(obj: PointObject): List<com.point.core.flow.NamedSheet> = delegate.readSheets(obj)
}
