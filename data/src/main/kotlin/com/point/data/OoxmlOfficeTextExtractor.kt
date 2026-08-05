package com.point.data

import com.point.core.flow.OfficeTextExtractor
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Разбор docx/xlsx/pptx переехал в `:core:flow` (#585) — он чистый, и его же читает компьютер.
 *
 * Здесь остался только переходник для Hilt: телефонная проводка просит `OfficeTextExtractor`, а
 * `@Inject` в общем ядре не живёт — там нет и не будет Android-зависимостей.
 */
class OoxmlOfficeTextExtractor @Inject constructor() : OfficeTextExtractor {
    private val delegate = com.point.core.flow.OoxmlOfficeTextExtractor()
    override suspend fun extractText(obj: PointObject): String = delegate.extractText(obj)
}
